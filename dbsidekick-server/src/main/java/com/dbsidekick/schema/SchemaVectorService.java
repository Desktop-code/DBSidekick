package com.dbsidekick.schema;

import com.dbsidekick.ai.embedding.LocalEmbeddingService;
import com.dbsidekick.ai.milvus.MilvusSchemaStore;
import com.dbsidekick.ai.milvus.SchemaChunk;
import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.relation.RelationUsage;
import com.dbsidekick.relation.RelationUsageService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 将 SQLite schema_table 向量化写入 Milvus，并提供自然语言表检索。
 */
@Service
public class SchemaVectorService {

    private static final Logger log = LoggerFactory.getLogger(SchemaVectorService.class);
    private static final int UPSERT_BATCH = 10;
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_CARD_CHARS = 400;
    private static final int MIN_FIELDS = 5;
    private static final int MAX_FIELDS = 10;

    private static final Pattern IMPORTANT_NAME = Pattern.compile(
            "(name|title|status|type|amount|price|total|count|qty|phone|mobile|email|address|create_time|update_time)",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "password", "token", "secret", "avatar", "remark", "create_by", "update_by",
            "pwd", "salt", "access_token", "refresh_token");

    private final SqliteInitializer sqliteInitializer;
    private final LocalEmbeddingService embeddingService;
    private final MilvusSchemaStore milvusSchemaStore;
    private final RelationUsageService relationUsageService;

    public SchemaVectorService(SqliteInitializer sqliteInitializer,
                               LocalEmbeddingService embeddingService,
                               MilvusSchemaStore milvusSchemaStore,
                               RelationUsageService relationUsageService) {
        this.sqliteInitializer = sqliteInitializer;
        this.embeddingService = embeddingService;
        this.milvusSchemaStore = milvusSchemaStore;
        this.relationUsageService = relationUsageService;
    }

    public IndexResult indexDatasource(String datasourceId) {
        return indexDatasource(datasourceId, null);
    }

    public IndexResult indexDatasource(String datasourceId, String database) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        if (!StringUtils.hasText(database)) {
            throw new IllegalArgumentException("请先选择数据库");
        }
        String db = database.trim();
        long start = System.currentTimeMillis();
        List<TableRow> rows = loadTables(datasourceId.trim(), db);
        if (rows.isEmpty()) {
            log.warn("[Sidekick] datasource={} db={} 无 schema_table 记录，请先同步 Schema", datasourceId, db);
            throw new IllegalArgumentException("该数据库尚未同步 Schema，请先展开或同步：" + db);
        }

        int total = rows.size();
        int indexed = 0;
        int skipped = 0;
        log.info("[Sidekick] indexing datasource={} db={} tables={}", datasourceId, db, total);

        milvusSchemaStore.deleteByDatasource(datasourceId.trim(), db);

        List<SchemaChunk> batch = new ArrayList<>(UPSERT_BATCH);
        for (int i = 0; i < rows.size(); i++) {
            TableRow row = rows.get(i);
            List<SchemaColumn> columns = loadColumns(row.id);
            SchemaTable table = toSchemaTable(row);
            String content = buildCard(datasourceId.trim(), table, columns, row.aliases, row.purpose);
            try {
                float[] vector = embeddingService.embed(content);
                SchemaChunk chunk = new SchemaChunk();
                chunk.setId(row.id);
                chunk.setContent(content);
                chunk.setDatasourceId(datasourceId.trim());
                chunk.setDbName(row.dbName);
                chunk.setTableName(row.tableName);
                chunk.setVector(vector);
                batch.add(chunk);
                indexed++;
            } catch (Exception ex) {
                skipped++;
                log.warn("[Sidekick] embed 失败 table={} id={}: {}", row.tableName, row.id, ex.getMessage());
            }

            int progress = i + 1;
            if (progress % 10 == 0 || progress == total) {
                log.info("[Sidekick] indexed {}/{}", progress, total);
            }

            if (batch.size() >= UPSERT_BATCH) {
                milvusSchemaStore.upsert(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            milvusSchemaStore.upsert(batch);
            batch.clear();
        }
        milvusSchemaStore.refresh();

        long elapsed = System.currentTimeMillis() - start;
        log.info("[Sidekick] index done datasource={} indexed={} skipped={} elapsedMs={}",
                datasourceId, indexed, skipped, elapsed);
        return new IndexResult(indexed, skipped, elapsed);
    }

    public List<SchemaHit> search(String datasourceId, String query, int topK) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        float[] vector = embeddingService.embed(query.trim());
        return milvusSchemaStore.search(datasourceId.trim(), vector, k);
    }

    /** 补全命中卡片 content（sparse 命中可能无向量文本）。 */
    public void enrichHitContent(String datasourceId, List<SchemaHit> hits) {
        if (!StringUtils.hasText(datasourceId) || hits == null || hits.isEmpty()) {
            return;
        }
        String ds = datasourceId.trim();
        for (SchemaHit hit : hits) {
            if (hit == null || !StringUtils.hasText(hit.getTableName())) {
                continue;
            }
            if (StringUtils.hasText(hit.getContent())) {
                continue;
            }
            String content = milvusSchemaStore.getTableContent(ds, hit.getTableName().trim());
            if (content != null) {
                hit.setContent(content);
            }
        }
    }

    /**
     * 检索入口别名（后续可挂关联扩展；本轮等同 {@link #search}）。
     */
    public List<SchemaHit> searchWithRelated(String datasourceId, String query, int topK) {
        return search(datasourceId, query, topK);
    }

    /**
     * 诊断检索：复用现有 search，不改召回逻辑；暴露 score / content 预览。
     */
    public Map<String, Object> debugSearch(String datasourceId, String query, int topK) {
        String q = query == null ? "" : query.trim();
        int k = topK <= 0 ? 10 : Math.min(topK, MAX_TOP_K);
        List<SchemaHit> hits = search(datasourceId, q, k);
        List<Map<String, Object>> hitMaps = new ArrayList<>();
        for (SchemaHit hit : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tableName", hit.getTableName());
            m.put("score", hit.getScore());
            m.put("isRelated", null);
            m.put("contentPreview", preview(hit.getContent(), 300));
            hitMaps.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("query", q);
        resp.put("topK", k);
        resp.put("hits", hitMaps);
        return resp;
    }

    /**
     * 诊断：返回 Milvus 中该表完整 content 卡片。
     */
    public Map<String, Object> getTableCard(String datasourceId, String tableName) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        if (!StringUtils.hasText(tableName)) {
            throw new IllegalArgumentException("tableName 不能为空");
        }
        String content = milvusSchemaStore.getTableContent(datasourceId.trim(), tableName.trim());
        if (content == null) {
            throw new IllegalArgumentException("Milvus 中未找到表卡片: " + tableName);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("datasourceId", datasourceId.trim());
        resp.put("tableName", tableName.trim());
        resp.put("content", content);
        resp.put("contentLength", content.length());
        return resp;
    }

    /**
     * 紧凑高密度表卡片（供向量化与 Prompt 检索片段）。
     */
    public String buildCard(String datasourceId, SchemaTable table, List<SchemaColumn> columns,
                            String aliases, String purpose) {
        String tableName = table == null ? "" : nullToEmpty(table.getTableName());
        String comment = table == null ? "" : nullToEmpty(table.getTableComment()).trim();

        StringBuilder head = new StringBuilder();
        head.append("【表名】").append(tableName);
        if (StringUtils.hasText(comment)) {
            head.append("（").append(comment).append("）");
        }
        head.append('\n');

        String aliasLine = formatAliases(aliases);
        if (StringUtils.hasText(aliasLine)) {
            head.append("【别名】").append(aliasLine).append('\n');
        }

        String purposeText = resolvePurpose(purpose, comment);
        head.append("【用途】").append(purposeText).append('\n');

        List<SchemaColumn> cols = columns == null ? List.of() : columns;
        List<String> relLines = buildRelationLines(datasourceId, tableName, cols);
        if (!relLines.isEmpty()) {
            head.append("【关联】\n");
            for (String line : relLines) {
                head.append(line).append('\n');
            }
        }

        List<SchemaColumn> selected = selectFields(cols);
        List<String> fieldLines = new ArrayList<>();
        for (SchemaColumn col : selected) {
            fieldLines.add(formatFieldLine(col));
        }

        return assembleWithLengthLimit(head.toString(), fieldLines);
    }

    private static String assembleWithLengthLimit(String head, List<String> fieldLines) {
        StringBuilder card = new StringBuilder(head);
        if (fieldLines == null || fieldLines.isEmpty()) {
            return card.toString().trim();
        }
        card.append("【字段】\n");
        int baseLen = card.length();
        List<String> kept = new ArrayList<>();
        for (String line : fieldLines) {
            kept.add(line);
            int len = baseLen;
            for (String k : kept) {
                len += k.length() + 1;
            }
            if (len > MAX_CARD_CHARS && kept.size() > 1) {
                kept.remove(kept.size() - 1);
                break;
            }
            if (len > MAX_CARD_CHARS && kept.size() == 1) {
                // 单字段仍超长：硬截断整卡（极端情况）
                break;
            }
        }
        for (String k : kept) {
            card.append(k).append('\n');
        }
        String out = card.toString().trim();
        if (out.length() > MAX_CARD_CHARS) {
            // 兜底：从末尾砍字段行
            while (out.length() > MAX_CARD_CHARS) {
                int lastNl = out.lastIndexOf('\n');
                if (lastNl <= head.length()) {
                    break;
                }
                out = out.substring(0, lastNl).trim();
            }
        }
        return out;
    }

    private static List<SchemaColumn> selectFields(List<SchemaColumn> columns) {
        List<SchemaColumn> eligible = new ArrayList<>();
        for (SchemaColumn col : columns) {
            if (col == null || !StringUtils.hasText(col.getColumnName())) {
                continue;
            }
            if (isExcluded(col.getColumnName())) {
                continue;
            }
            eligible.add(col);
        }
        eligible.sort(Comparator.comparingInt(SchemaColumn::getOrdinalPosition));

        LinkedHashSet<SchemaColumn> must = new LinkedHashSet<>();
        List<SchemaColumn> fillers = new ArrayList<>();
        for (SchemaColumn col : eligible) {
            if (col.isPrimaryKey()
                    || StringUtils.hasText(col.getForeignKeyRef())
                    || isLikelyFkName(col.getColumnName())
                    || isImportantName(col.getColumnName())) {
                must.add(col);
            } else if (StringUtils.hasText(col.getColumnComment())) {
                fillers.add(col);
            }
        }

        List<SchemaColumn> selected = new ArrayList<>(must);
        selected.sort(Comparator.comparingInt(SchemaColumn::getOrdinalPosition));
        for (SchemaColumn col : fillers) {
            if (selected.size() >= MIN_FIELDS) {
                break;
            }
            if (!selected.contains(col)) {
                selected.add(col);
            }
        }
        selected.sort(Comparator.comparingInt(SchemaColumn::getOrdinalPosition));

        if (selected.size() > MAX_FIELDS) {
            selected = prioritizeAndLimit(selected, MAX_FIELDS);
        }
        return selected;
    }

    private static List<SchemaColumn> prioritizeAndLimit(List<SchemaColumn> cols, int limit) {
        List<SchemaColumn> ranked = new ArrayList<>(cols);
        ranked.sort((a, b) -> {
            int pa = fieldPriority(a);
            int pb = fieldPriority(b);
            if (pa != pb) {
                return Integer.compare(pa, pb);
            }
            return Integer.compare(a.getOrdinalPosition(), b.getOrdinalPosition());
        });
        List<SchemaColumn> top = new ArrayList<>(ranked.subList(0, Math.min(limit, ranked.size())));
        top.sort(Comparator.comparingInt(SchemaColumn::getOrdinalPosition));
        return top;
    }

    /** 越小越优先：主键 > 外键 > 疑似外键(_id) > 重要名 > 其它 */
    private static int fieldPriority(SchemaColumn col) {
        if (col.isPrimaryKey()) {
            return 0;
        }
        if (StringUtils.hasText(col.getForeignKeyRef())) {
            return 1;
        }
        if (isLikelyFkName(col.getColumnName())) {
            return 2;
        }
        if (isImportantName(col.getColumnName())) {
            return 3;
        }
        return 4;
    }

    private List<String> buildRelationLines(String datasourceId, String tableName, List<SchemaColumn> columns) {
        List<String> lines = new ArrayList<>();
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(tableName)) {
            return lines;
        }
        List<RelationUsage> rels = relationUsageService.listConfirmedTouching(datasourceId, tableName);
        for (RelationUsage rel : rels) {
            if (rel == null) {
                continue;
            }
            boolean onSource = tableName.equalsIgnoreCase(rel.getSourceTable());
            String column = onSource ? rel.getSourceColumn() : rel.getTargetColumn();
            String ref = onSource
                    ? rel.getTargetTable() + "." + rel.getTargetColumn()
                    : rel.getSourceTable() + "." + rel.getSourceColumn();
            if (!StringUtils.hasText(column) || !StringUtils.hasText(ref)) {
                continue;
            }
            String comment = commentOf(columns, column);
            StringBuilder line = new StringBuilder();
            line.append("- ").append(column).append(" → ").append(ref);
            if (StringUtils.hasText(comment)) {
                line.append("（").append(comment).append("）");
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static String commentOf(List<SchemaColumn> columns, String columnName) {
        if (columns == null || !StringUtils.hasText(columnName)) {
            return "";
        }
        for (SchemaColumn col : columns) {
            if (col != null && columnName.equalsIgnoreCase(col.getColumnName())
                    && StringUtils.hasText(col.getColumnComment())) {
                return col.getColumnComment().trim();
            }
        }
        return "";
    }

    private static String formatFieldLine(SchemaColumn col) {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(col.getColumnName()).append(" (");
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(col.getColumnComment())) {
            parts.add(col.getColumnComment().trim());
        }
        if (col.isPrimaryKey()) {
            parts.add("主键");
        }
        if (StringUtils.hasText(col.getForeignKeyRef())) {
            parts.add("外键");
        }
        if (parts.isEmpty()) {
            parts.add(col.getColumnName());
        }
        sb.append(String.join(", ", parts));
        sb.append(')');
        return sb.toString();
    }

    private static String formatAliases(String aliases) {
        if (!StringUtils.hasText(aliases)) {
            return "";
        }
        String[] parts = aliases.split("[,，、;；/|]");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (StringUtils.hasText(s) && !out.contains(s)) {
                out.add(s);
            }
        }
        return String.join(" / ", out);
    }

    private static String resolvePurpose(String purpose, String comment) {
        if (StringUtils.hasText(purpose)) {
            return purpose.trim();
        }
        if (StringUtils.hasText(comment)) {
            return comment.trim();
        }
        return "暂无说明";
    }

    private static boolean isExcluded(String columnName) {
        String n = columnName.toLowerCase(Locale.ROOT);
        if (EXCLUDED_NAMES.contains(n)) {
            return true;
        }
        return n.contains("password") || n.contains("secret") || n.endsWith("_token");
    }

    private static boolean isImportantName(String columnName) {
        return IMPORTANT_NAME.matcher(columnName).find();
    }

    /** 无 FK 元数据时，仍保留非主键的 *_id 列（如 dept_id），便于检索与 JOIN 提示。 */
    private static boolean isLikelyFkName(String columnName) {
        if (!StringUtils.hasText(columnName)) {
            return false;
        }
        String n = columnName.toLowerCase(Locale.ROOT);
        return n.endsWith("_id") && !n.equals("id");
    }

    private static SchemaTable toSchemaTable(TableRow row) {
        SchemaTable t = new SchemaTable();
        t.setTableName(row.tableName);
        t.setTableComment(row.tableComment);
        t.setTableAliases(row.aliases);
        t.setTablePurpose(row.purpose);
        return t;
    }

    private static String preview(String content, int max) {
        if (content == null) {
            return "";
        }
        String t = content.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private List<TableRow> loadTables(String datasourceId) {
        return loadTables(datasourceId, null);
    }

    private List<TableRow> loadTables(String datasourceId, String database) {
        List<TableRow> rows = new ArrayList<>();
        String sql = """
                SELECT id, table_name, table_comment, db_name, table_aliases, table_purpose
                FROM schema_table
                WHERE datasource_id = ?
                """ + (StringUtils.hasText(database) ? " AND db_name = ?" : "") + """
                ORDER BY table_name
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            if (StringUtils.hasText(database)) {
                ps.setString(2, database.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableRow row = new TableRow();
                    row.id = rs.getString("id");
                    row.tableName = rs.getString("table_name");
                    row.tableComment = rs.getString("table_comment");
                    row.dbName = rs.getString("db_name");
                    row.aliases = rs.getString("table_aliases");
                    row.purpose = rs.getString("table_purpose");
                    rows.add(row);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取 schema_table 失败: " + ex.getMessage(), ex);
        }
        return rows;
    }

    private List<SchemaColumn> loadColumns(String tableId) {
        List<SchemaColumn> list = new ArrayList<>();
        String sql = """
                SELECT column_name, data_type, column_comment, is_primary_key, is_nullable,
                       foreign_key_ref, ordinal_position
                FROM schema_column
                WHERE table_id = ?
                ORDER BY ordinal_position
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SchemaColumn col = new SchemaColumn();
                    col.setColumnName(rs.getString("column_name"));
                    col.setDataType(rs.getString("data_type"));
                    col.setColumnComment(rs.getString("column_comment"));
                    col.setPrimaryKey(rs.getInt("is_primary_key") == 1);
                    col.setNullable(rs.getInt("is_nullable") == 1);
                    col.setForeignKeyRef(rs.getString("foreign_key_ref"));
                    col.setOrdinalPosition(rs.getInt("ordinal_position"));
                    list.add(col);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取 schema_column 失败: " + ex.getMessage(), ex);
        }
        return list;
    }

    public static final class IndexResult {
        private final int indexedCount;
        private final int skippedCount;
        private final long elapsedMs;

        public IndexResult(int indexedCount, int skippedCount, long elapsedMs) {
            this.indexedCount = indexedCount;
            this.skippedCount = skippedCount;
            this.elapsedMs = elapsedMs;
        }

        public int getIndexedCount() {
            return indexedCount;
        }

        public int getSkippedCount() {
            return skippedCount;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("indexedCount", indexedCount);
            map.put("skippedCount", skippedCount);
            map.put("elapsedMs", elapsedMs);
            return map;
        }
    }

    private static final class TableRow {
        private String id;
        private String tableName;
        private String tableComment;
        private String dbName;
        private String aliases;
        private String purpose;
    }
}
