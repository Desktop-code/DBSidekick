package com.dbsidekick.relation;

import com.dbsidekick.config.SqliteInitializer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * relation_usage、usage_log，以及迁移要用的 settings / relation_config / schema 只读。
 * 不解释分数，不决定晋升。
 */
@Repository
public class RelationUsageRepository {

    private static final Logger log = LoggerFactory.getLogger(RelationUsageRepository.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String SELECT_FROM = "SELECT id, datasource_id, source_table, source_column, "
            + "target_table, target_column, hit_count, confidence, status, sources, first_seen_at, "
            + "last_used_at, last_use_rows FROM relation_usage";

    private final SqliteInitializer sqliteInitializer;

    public RelationUsageRepository(SqliteInitializer sqliteInitializer) {
        this.sqliteInitializer = sqliteInitializer;
    }
    /** 表注释与列数，供 ER 图节点使用。 */
    public record SchemaTableMeta(String name, String comment, int columnCount) {
        public static SchemaTableMeta empty(String name) {
            return new SchemaTableMeta(name, "", 0);
        }
    }
    /** schema_column 上的旧推断外键，解析留在门面。 */
    public record InferredFkRow(String datasourceId, String tableName, String columnName, String foreignKeyRef) {
    }
    /** 已确认关系的两端表名。 */
    public record TableEdge(String sourceTable, String targetTable) {
    }
    public RelationUsage findById(String id) throws Exception {
        return queryOne(SELECT_FROM + " WHERE id = ?", ps -> ps.setString(1, id));
    }
    public RelationUsage findByPair(String datasourceId, RelationPair pair) throws Exception {
        String sql = SELECT_FROM + " WHERE datasource_id = ? AND source_table = ? AND source_column = ?"
                + " AND target_table = ? AND target_column = ?";
        return queryOne(sql, ps -> {
            ps.setString(1, datasourceId);
            ps.setString(2, pair.sourceTable());
            ps.setString(3, pair.sourceColumn());
            ps.setString(4, pair.targetTable());
            ps.setString(5, pair.targetColumn());
        });
    }
    public void insert(RelationUsage row) throws Exception {
        String sql = "INSERT INTO relation_usage(id, datasource_id, source_table, source_column, target_table, "
                + "target_column, hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        execute(sql, ps -> {
            ps.setString(1, row.getId());
            ps.setString(2, row.getDatasourceId());
            ps.setString(3, row.getSourceTable());
            ps.setString(4, row.getSourceColumn());
            ps.setString(5, row.getTargetTable());
            ps.setString(6, row.getTargetColumn());
            ps.setInt(7, row.getHitCount());
            ps.setInt(8, row.getConfidence());
            ps.setString(9, row.getStatus());
            ps.setString(10, row.getSources());
            ps.setString(11, row.getFirstSeenAt());
            ps.setString(12, row.getLastUsedAt());
            setNullableInt(ps, 13, row.getLastUseRows());
        });
    }
    public void update(RelationUsage row) throws Exception {
        String sql = "UPDATE relation_usage SET hit_count = ?, confidence = ?, status = ?, sources = ?, "
                + "last_used_at = ?, last_use_rows = ? WHERE id = ?";
        execute(sql, ps -> {
            ps.setInt(1, row.getHitCount());
            ps.setInt(2, row.getConfidence());
            ps.setString(3, row.getStatus());
            ps.setString(4, row.getSources());
            ps.setString(5, row.getLastUsedAt());
            setNullableInt(ps, 6, row.getLastUseRows());
            ps.setString(7, row.getId());
        });
    }
    public void deleteById(String id) throws Exception {
        execute("DELETE FROM relation_usage WHERE id = ?", ps -> ps.setString(1, id));
    }
    /** 先删日志再删关系。返回删掉的 relation_usage 行数。 */
    public int deleteByDatasource(String datasourceId) throws Exception {
        execute("DELETE FROM relation_usage_log WHERE relation_id IN "
                + "(SELECT id FROM relation_usage WHERE datasource_id = ?)", ps -> ps.setString(1, datasourceId));
        return executeUpdate("DELETE FROM relation_usage WHERE datasource_id = ?", ps -> ps.setString(1, datasourceId));
    }
    public List<RelationUsage> list(String datasourceId, String database, String status, String keyword,
                                    int limit, int offset) throws Exception {
        StringBuilder sql = new StringBuilder(SELECT_FROM + " WHERE datasource_id = ?");
        appendListFilters(sql, database, status, keyword);
        sql.append(" ORDER BY confidence DESC, CASE WHEN last_used_at IS NULL THEN 1 ELSE 0 END, "
                + "last_used_at DESC, source_table, source_column LIMIT ? OFFSET ?");
        return query(sql.toString(), ps -> {
            int next = bindListFilters(ps, 1, datasourceId, database, status, keyword);
            ps.setInt(next, limit);
            ps.setInt(next + 1, offset);
        });
    }
    public int count(String datasourceId, String database, String status, String keyword) throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM relation_usage WHERE datasource_id = ?");
        appendListFilters(sql, database, status, keyword);
        String raw = queryString(sql.toString(), ps -> bindListFilters(ps, 1, datasourceId, database, status, keyword));
        return raw == null ? 0 : Integer.parseInt(raw);
    }
    /** ER 图用全量边。库过滤在组装层做，这里不按 database 收窄。 */
    public List<RelationUsage> listForGraph(String datasourceId, String status) throws Exception {
        StringBuilder sql = new StringBuilder(SELECT_FROM + " WHERE datasource_id = ?");
        appendListFilters(sql, null, status, null);
        sql.append(" ORDER BY confidence DESC, source_table, source_column");
        return query(sql.toString(), ps -> bindListFilters(ps, 1, datasourceId, null, status, null));
    }
    public List<RelationUsage> listConfirmedTouching(String datasourceId, String tableName) throws Exception {
        String sql = SELECT_FROM + " WHERE datasource_id = ? AND status = 'CONFIRMED' AND "
                + "(LOWER(source_table) = LOWER(?) OR LOWER(target_table) = LOWER(?)) "
                + "ORDER BY confidence DESC, source_table, source_column";
        return query(sql, ps -> {
            ps.setString(1, datasourceId);
            ps.setString(2, tableName);
            ps.setString(3, tableName);
        });
    }
    public List<TableEdge> listConfirmedEdges(String datasourceId) throws Exception {
        String sql = "SELECT source_table, target_table FROM relation_usage "
                + "WHERE datasource_id = ? AND status = 'CONFIRMED'";
        List<TableEdge> edges = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    edges.add(new TableEdge(rs.getString("source_table"), rs.getString("target_table")));
                }
            }
        }
        return edges;
    }
    public List<RelationUsage> listByStatus(String status) throws Exception {
        return query(SELECT_FROM + " WHERE status = ?", ps -> ps.setString(1, status));
    }
    public Set<String> loadTableNames(String datasourceId, String database) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT table_name FROM schema_table WHERE datasource_id = ? AND db_name = ?";
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            ps.setString(2, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (StringUtils.hasText(name)) {
                        out.add(name.trim());
                    }
                }
            }
        }
        return out;
    }
    public Map<String, SchemaTableMeta> loadTableMeta(String datasourceId, String database, Collection<String> tables) {
        Map<String, SchemaTableMeta> out = new LinkedHashMap<>();
        if (tables == null || tables.isEmpty()) {
            return out;
        }
        StringBuilder sql = new StringBuilder("SELECT t.table_name, COALESCE(NULLIF(TRIM(t.table_comment), ''), "
                + "NULLIF(TRIM(t.table_purpose), ''), '') AS comment, "
                + "(SELECT COUNT(*) FROM schema_column c WHERE c.table_id = t.id) AS col_count "
                + "FROM schema_table t WHERE t.datasource_id = ?");
        if (StringUtils.hasText(database)) {
            sql.append(" AND t.db_name = ?");
        }
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, datasourceId);
            if (StringUtils.hasText(database)) {
                ps.setString(2, database.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    if (!tables.contains(name)) {
                        continue;
                    }
                    String comment = rs.getString("comment");
                    out.put(name, new SchemaTableMeta(name, comment == null ? "" : comment, rs.getInt("col_count")));
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] load table meta failed: {}", ex.getMessage());
        }
        return out;
    }
    public void writeLog(String relationId, String sessionId, String questionHash, int delta, String source)
            throws Exception {
        String sql = "INSERT INTO relation_usage_log(id, relation_id, session_id, question_hash, delta, source, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        execute(sql, ps -> {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, relationId);
            ps.setString(3, sessionId);
            ps.setString(4, questionHash);
            ps.setInt(5, delta);
            ps.setString(6, source);
            ps.setString(7, now());
        });
    }
    public boolean alreadyScored(String relationId, String sessionId, String questionHash) throws Exception {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(questionHash)) {
            return false;
        }
        String sql = "SELECT 1 FROM relation_usage_log WHERE relation_id = ? AND session_id = ? "
                + "AND question_hash = ? LIMIT 1";
        return queryString(sql, ps -> {
            ps.setString(1, relationId);
            ps.setString(2, sessionId);
            ps.setString(3, questionHash);
        }) != null;
    }
    /** 最近来源，新的在前，最多 20 条。连续点踩由计分策略数。 */
    public List<String> recentLogSources(String relationId) throws Exception {
        String sql = "SELECT source FROM relation_usage_log WHERE relation_id = ? "
                + "ORDER BY created_at DESC, rowid DESC LIMIT 20";
        List<String> sources = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sources.add(rs.getString("source"));
                }
            }
        }
        return sources;
    }
    public int countAll() throws Exception {
        String raw = queryString("SELECT COUNT(*) FROM relation_usage", ps -> { });
        return raw == null ? 0 : Integer.parseInt(raw);
    }
    public List<InferredFkRow> selectInferredFkRows() throws Exception {
        String sql = "SELECT t.datasource_id, t.table_name, c.column_name, c.foreign_key_ref "
                + "FROM schema_column c JOIN schema_table t ON t.id = c.table_id "
                + "WHERE IFNULL(c.is_inferred, 0) = 1 AND c.foreign_key_ref IS NOT NULL "
                + "AND TRIM(c.foreign_key_ref) <> ''";
        List<InferredFkRow> rows = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new InferredFkRow(rs.getString("datasource_id"), rs.getString("table_name"),
                        rs.getString("column_name"), rs.getString("foreign_key_ref")));
            }
        }
        return rows;
    }
    public boolean settingIsTrue(String key) throws Exception {
        String value = queryString("SELECT value FROM app_settings WHERE key = ?", ps -> ps.setString(1, key));
        return "true".equalsIgnoreCase(value);
    }
    public void writeSetting(String key, String value) throws Exception {
        String sql = "INSERT INTO app_settings(key, value, updated_at) VALUES(?, ?, ?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at";
        execute(sql, ps -> {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, now());
        });
    }
    public String readConfig(String key) throws Exception {
        return queryString("SELECT value FROM relation_config WHERE key = ?", ps -> ps.setString(1, key));
    }
    public void writeConfig(String key, String value) throws Exception {
        execute("UPDATE relation_config SET value = ?, updated_at = ? WHERE key = ?", ps -> {
            ps.setString(1, value);
            ps.setString(2, now());
            ps.setString(3, key);
        });
    }
    private List<RelationUsage> query(String sql, SqlBinder binder) throws Exception {
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<RelationUsage> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
                return list;
            }
        }
    }
    private RelationUsage queryOne(String sql, SqlBinder binder) throws Exception {
        List<RelationUsage> list = query(sql, binder);
        return list.isEmpty() ? null : list.get(0);
    }
    private String queryString(String sql, SqlBinder binder) throws Exception {
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
    private void execute(String sql, SqlBinder binder) throws Exception {
        executeUpdate(sql, binder);
    }
    private int executeUpdate(String sql, SqlBinder binder) throws Exception {
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        }
    }
    private static void appendListFilters(StringBuilder sql, String database, String status, String keyword) {
        if (StringUtils.hasText(database)) {
            sql.append(" AND source_table IN (SELECT table_name FROM schema_table "
                    + "WHERE datasource_id = ? AND db_name = ?) AND target_table IN "
                    + "(SELECT table_name FROM schema_table WHERE datasource_id = ? AND db_name = ?)");
        }
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ?");
        }
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (source_table LIKE ? OR source_column LIKE ? OR target_table LIKE ? "
                    + "OR target_column LIKE ?)");
        }
    }
    private static int bindListFilters(PreparedStatement ps, int start, String ds, String database,
                                       String status, String keyword) throws Exception {
        int i = start;
        ps.setString(i++, ds);
        if (StringUtils.hasText(database)) {
            String db = database.trim();
            ps.setString(i++, ds);
            ps.setString(i++, db);
            ps.setString(i++, ds);
            ps.setString(i++, db);
        }
        if (StringUtils.hasText(status)) {
            ps.setString(i++, status.trim().toUpperCase(Locale.ROOT));
        }
        if (StringUtils.hasText(keyword)) {
            String like = "%" + keyword.trim() + "%";
            ps.setString(i++, like);
            ps.setString(i++, like);
            ps.setString(i++, like);
            ps.setString(i++, like);
        }
        return i;
    }
    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws Exception {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
    private static RelationUsage mapRow(ResultSet rs) throws Exception {
        RelationUsage row = new RelationUsage();
        row.setId(rs.getString("id"));
        row.setDatasourceId(rs.getString("datasource_id"));
        row.setSourceTable(rs.getString("source_table"));
        row.setSourceColumn(rs.getString("source_column"));
        row.setTargetTable(rs.getString("target_table"));
        row.setTargetColumn(rs.getString("target_column"));
        row.setHitCount(rs.getInt("hit_count"));
        row.setConfidence(rs.getInt("confidence"));
        row.setStatus(rs.getString("status"));
        row.setSources(rs.getString("sources"));
        row.setFirstSeenAt(rs.getString("first_seen_at"));
        row.setLastUsedAt(rs.getString("last_used_at"));
        int rows = rs.getInt("last_use_rows");
        row.setLastUseRows(rs.wasNull() ? null : rows);
        return row;
    }
    private static String now() {
        return TS.format(Instant.now());
    }
    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement ps) throws Exception;
    }
}
