package com.dbsidekick.schema;

import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.relation.RelationPair;
import com.dbsidekick.relation.RelationUsageService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 四层过滤的外键推断（无跨库、无多跳、无 LLM）。
 */
@Service
public class RelationInferService {

    private static final Logger log = LoggerFactory.getLogger(RelationInferService.class);

    private static final Pattern COL_ID = Pattern.compile("^(.+?)_id$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COL_CAMEL_ID = Pattern.compile("^(.+?)Id$");

    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "id", "uuid", "guid", "code", "no", "num", "sort", "order");

    /** 已知业务域前缀种子；实际以库内出现 ≥2 次为准。 */
    private static final String[] KNOWN_DOMAIN_SEEDS = {
            "sys_", "rtl_", "hskj_", "blog_", "asst_", "sht_", "qrtz_", "act_", "gen_", "im_", "rag_"
    };

    private static final Set<String> CROSS_DOMAIN_WHITELIST = Set.of(
            "sys_user", "sys_dept", "sys_dict", "sys_dict_data", "sys_dict_type");

    private static final int SCORE_SAME_DOMAIN = 10;
    private static final int SCORE_PK_PREFIX_ID = 5;
    private static final int SCORE_PK_ID = 3;
    private static final int SCORE_NAME_CONTAINS = 2;
    private static final int MIN_SCORE = 5;
    private static final int MIN_SCORE_GAP = 3;

    private final SqliteInitializer sqliteInitializer;
    private final RelationUsageService relationUsageService;

    public RelationInferService(SqliteInitializer sqliteInitializer,
                                RelationUsageService relationUsageService) {
        this.sqliteInitializer = sqliteInitializer;
        this.relationUsageService = relationUsageService;
    }

    public InferReport infer(String datasourceId) {
        return infer(datasourceId, null);
    }

    public InferReport infer(String datasourceId, String database) {
        long start = System.currentTimeMillis();
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String ds = datasourceId.trim();
        String db = StringUtils.hasText(database) ? database.trim() : null;

        try (Connection conn = sqliteInitializer.open()) {
            List<TableMeta> tables = loadTables(conn, ds, db);
            if (tables.isEmpty()) {
                throw new IllegalArgumentException("该数据源尚未同步 Schema，请先同步");
            }
            Map<String, String> pkByTable = loadPrimaryKeys(conn, ds, db);
            Set<String> domainPrefixes = detectDomainPrefixes(tables);

            int inferred = 0;
            int existing = 0;
            int skipped = 0;

            for (TableMeta table : tables) {
                List<ColumnMeta> columns = loadColumns(conn, table.id);
                for (ColumnMeta col : columns) {
                    Decision d = decide(table.tableName, col, tables, pkByTable, domainPrefixes);
                    if (!d.accepted()) {
                        if (d.considered()) {
                            skipped++;
                        }
                        continue;
                    }
                    // 写入隐式关系库；不再写 schema_column.foreign_key_ref
                    String ref = d.ref();
                    int dot = ref.lastIndexOf('.');
                    String targetTable = dot > 0 ? ref.substring(0, dot) : ref;
                    String targetColumn = dot > 0 ? ref.substring(dot + 1) : "";
                    RelationPair pair = RelationPair.normalized(
                            table.tableName, col.columnName, targetTable, targetColumn);
                    boolean inserted = relationUsageService.upsertInferred(ds, pair);
                    if (inserted) {
                        inferred++;
                    } else {
                        existing++;
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("[Sidekick][infer] datasource={} inferred={} existing={} skipped={} elapsedMs={}",
                    ds, inferred, existing, skipped, elapsed);
            return new InferReport(inferred, existing, elapsed);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("外键推断失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 调试单字段推断过程。
     */
    public Map<String, Object> debugColumn(String datasourceId, String columnName) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(columnName)) {
            throw new IllegalArgumentException("datasourceId / columnName 不能为空");
        }
        String ds = datasourceId.trim();
        String colName = columnName.trim();
        try (Connection conn = sqliteInitializer.open()) {
            List<TableMeta> tables = loadTables(conn, ds, null);
            Map<String, String> pkByTable = loadPrimaryKeys(conn, ds, null);
            Set<String> domainPrefixes = detectDomainPrefixes(tables);

            List<Map<String, Object>> matches = new ArrayList<>();
            for (TableMeta table : tables) {
                for (ColumnMeta col : loadColumns(conn, table.id)) {
                    if (!colName.equalsIgnoreCase(col.columnName)) {
                        continue;
                    }
                    Decision d = decide(table.tableName, col, tables, pkByTable, domainPrefixes);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sourceTable", table.tableName);
                    row.put("column", col.columnName);
                    row.put("primaryKey", col.primaryKey);
                    row.put("existingFk", col.foreignKeyRef);
                    row.put("prefix", d.prefix());
                    row.put("candidates", d.candidateDetails());
                    row.put("accepted", d.accepted());
                    row.put("ref", d.ref());
                    row.put("skipReason", d.skipReason());
                    row.put("layers", d.layerNotes());
                    matches.add(row);
                }
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("datasourceId", ds);
            resp.put("columnName", colName);
            resp.put("domainPrefixes", new ArrayList<>(domainPrefixes));
            resp.put("matches", matches);
            return resp;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("infer-debug 失败: " + ex.getMessage(), ex);
        }
    }

    private Decision decide(String sourceTable,
                            ColumnMeta col,
                            List<TableMeta> tables,
                            Map<String, String> pkByTable,
                            Set<String> domainPrefixes) {
        Decision d = new Decision();
        if (col.primaryKey) {
            d.skipReason = "字段是主键";
            return d;
        }
        if (StringUtils.hasText(col.foreignKeyRef) && (col.inferred == null || !col.inferred)) {
            // 真实外键保留：推断时已 clear is_inferred，真实 FK 仍有值
            d.skipReason = "已有真实外键";
            return d;
        }
        if (StringUtils.hasText(col.foreignKeyRef) && Boolean.FALSE.equals(col.inferred)) {
            d.skipReason = "已有真实外键";
            return d;
        }

        String prefix = extractPrefix(col.columnName);
        d.prefix = prefix;
        if (!StringUtils.hasText(prefix)) {
            d.skipReason = "字段名不匹配 *_id";
            return d;
        }
        String prefixKey = prefix.toLowerCase(Locale.ROOT);
        if (EXCLUDED_PREFIXES.contains(prefixKey)) {
            d.skipReason = "通用词排除: " + prefixKey;
            return d;
        }
        d.considered = true;

        // —— 第一层：收集候选 ——
        List<String> candidates = findCandidates(prefixKey, tables);
        d.layerNotes.add("L1候选=" + candidates);
        if (candidates.isEmpty()) {
            d.skipReason = "L1无候选表";
            return d;
        }

        // 去掉自身
        candidates = candidates.stream()
                .filter(t -> !t.equalsIgnoreCase(sourceTable))
                .toList();
        if (candidates.isEmpty()) {
            d.skipReason = "L1仅匹配自身";
            return d;
        }

        String chosen;
        if (candidates.size() == 1) {
            chosen = candidates.get(0);
            d.layerNotes.add("L1唯一候选=" + chosen);
            int score = scoreCandidate(sourceTable, prefixKey, chosen, pkByTable, domainPrefixes);
            d.candidateDetails.add(candidateDetail(chosen, score, pkByTable));
            d.layerNotes.add("L2唯一候选评分=" + score);
            // 唯一候选也要求分数达到门槛，避免弱匹配
            if (score < MIN_SCORE) {
                d.skipReason = "L2唯一候选分不足(" + score + "<" + MIN_SCORE + ")";
                return d;
            }
        } else {
            // —— 第二层：打分 ——
            List<Scored> scored = new ArrayList<>();
            for (String t : candidates) {
                int s = scoreCandidate(sourceTable, prefixKey, t, pkByTable, domainPrefixes);
                scored.add(new Scored(t, s));
                d.candidateDetails.add(candidateDetail(t, s, pkByTable));
            }
            scored.sort(Comparator.comparingInt(Scored::score).reversed());
            d.layerNotes.add("L2打分=" + scored);
            if (scored.get(0).score() < MIN_SCORE) {
                d.skipReason = "L2最高分不足(" + scored.get(0).score() + "<" + MIN_SCORE + ")";
                return d;
            }
            if (scored.size() > 1 && scored.get(0).score() - scored.get(1).score() < MIN_SCORE_GAP) {
                d.skipReason = "L2分差不足(" + scored.get(0).score() + "-" + scored.get(1).score()
                        + "<" + MIN_SCORE_GAP + ")";
                return d;
            }
            chosen = scored.get(0).table();
        }

        // —— 第三层：跨业务域 ——
        String srcDomain = extractDomain(sourceTable, domainPrefixes);
        String tgtDomain = extractDomain(chosen, domainPrefixes);
        d.layerNotes.add("L3源域=" + srcDomain + " 目标域=" + tgtDomain);
        if (StringUtils.hasText(srcDomain)
                && StringUtils.hasText(tgtDomain)
                && !srcDomain.equals(tgtDomain)
                && !CROSS_DOMAIN_WHITELIST.contains(chosen.toLowerCase(Locale.ROOT))) {
            d.skipReason = "L3跨业务域 " + srcDomain + "→" + tgtDomain;
            return d;
        }

        // —— 第四层：主键名一致性 ——
        String pk = pkByTable.get(chosen);
        d.layerNotes.add("L4目标主键=" + pk);
        if (!pkAllowed(prefixKey, pk, col.columnName)) {
            d.skipReason = "L4主键名不一致(pk=" + pk + ", expect=" + prefixKey + "_id|与字段同名)";
            return d;
        }
        if (!StringUtils.hasText(pk)) {
            d.skipReason = "L4目标表无主键";
            return d;
        }

        d.accepted = true;
        d.ref = chosen + "." + pk;
        d.skipReason = null;
        return d;
    }

    private static final Set<String> WEAK_PREFIXES = Set.of(
            "category", "type", "status", "parent", "group", "kind", "class", "level");

    private static boolean pkAllowed(String prefixKey, String pk, String fkColumn) {
        if (!StringUtils.hasText(pk)) {
            return false;
        }
        String p = pk.toLowerCase(Locale.ROOT);
        String expect = prefixKey + "_id";
        if (expect.equals(p)) {
            return true;
        }
        if (fkColumn != null && fkColumn.equalsIgnoreCase(pk)) {
            return true;
        }
        // 裸 id：允许，但对弱语义前缀（category/type…）跳过，避免误连
        if ("id".equals(p)) {
            return !WEAK_PREFIXES.contains(prefixKey.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static int scoreCandidate(String sourceTable,
                                      String prefixKey,
                                      String candidate,
                                      Map<String, String> pkByTable,
                                      Set<String> domainPrefixes) {
        int score = 0;
        String srcDomain = extractDomain(sourceTable, domainPrefixes);
        String tgtDomain = extractDomain(candidate, domainPrefixes);
        if (StringUtils.hasText(srcDomain) && srcDomain.equals(tgtDomain)) {
            score += SCORE_SAME_DOMAIN;
        }
        String pk = pkByTable.get(candidate);
        if (StringUtils.hasText(pk)) {
            String pl = pk.toLowerCase(Locale.ROOT);
            if ((prefixKey + "_id").equals(pl)) {
                score += SCORE_PK_PREFIX_ID;
            } else if ("id".equals(pl)) {
                score += SCORE_PK_ID;
            }
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        String stripped = stripKnownPrefix(lower, domainPrefixes);
        if (stripped.contains(prefixKey) || lower.contains(prefixKey)) {
            score += SCORE_NAME_CONTAINS;
        }
        return score;
    }

    private static List<String> findCandidates(String prefixKey, List<TableMeta> tables) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            String lower = t.tableName.toLowerCase(Locale.ROOT);
            String stripped = stripSeedPrefix(lower);
            if (prefixKey.equals(stripped)
                    || prefixKey.equals(singularize(stripped))
                    || prefixKey.equals(pluralize(stripped))
                    || lower.equals(prefixKey)
                    || lower.equals(prefixKey + "s")) {
                out.put(t.tableName, Boolean.TRUE);
            }
        }
        return new ArrayList<>(out.keySet());
    }

    private static Set<String> detectDomainPrefixes(List<TableMeta> tables) {
        Map<String, Integer> counts = new HashMap<>();
        for (String seed : KNOWN_DOMAIN_SEEDS) {
            counts.put(seed, 0);
        }
        // 动态：取表名第一个 _ 前缀
        for (TableMeta t : tables) {
            String lower = t.tableName.toLowerCase(Locale.ROOT);
            for (String seed : KNOWN_DOMAIN_SEEDS) {
                if (lower.startsWith(seed)) {
                    counts.merge(seed, 1, Integer::sum);
                }
            }
            int us = lower.indexOf('_');
            if (us > 0 && us < lower.length() - 1) {
                String dyn = lower.substring(0, us + 1);
                counts.merge(dyn, 1, Integer::sum);
            }
        }
        Set<String> domains = new HashSet<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) {
                domains.add(e.getKey());
            }
        }
        return domains;
    }

    private static String extractDomain(String tableName, Set<String> domainPrefixes) {
        if (!StringUtils.hasText(tableName)) {
            return null;
        }
        String lower = tableName.toLowerCase(Locale.ROOT);
        String best = null;
        for (String p : domainPrefixes) {
            if (lower.startsWith(p) && (best == null || p.length() > best.length())) {
                best = p;
            }
        }
        return best;
    }

    private static String stripKnownPrefix(String lower, Set<String> domainPrefixes) {
        String best = null;
        for (String p : domainPrefixes) {
            if (lower.startsWith(p) && (best == null || p.length() > best.length())) {
                best = p;
            }
        }
        if (best != null && lower.length() > best.length()) {
            return lower.substring(best.length());
        }
        return stripSeedPrefix(lower);
    }

    private static String stripSeedPrefix(String lower) {
        for (String p : KNOWN_DOMAIN_SEEDS) {
            if (lower.startsWith(p) && lower.length() > p.length()) {
                return lower.substring(p.length());
            }
        }
        return lower;
    }

    private static Map<String, Object> candidateDetail(String table, int score, Map<String, String> pkByTable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("table", table);
        m.put("score", score);
        m.put("pk", pkByTable.get(table));
        return m;
    }

    private static String pluralize(String singular) {
        if (!StringUtils.hasText(singular)) {
            return singular;
        }
        if (singular.endsWith("y") && singular.length() > 1
                && "aeiou".indexOf(singular.charAt(singular.length() - 2)) < 0) {
            return singular.substring(0, singular.length() - 1) + "ies";
        }
        if (singular.endsWith("s") || singular.endsWith("x")
                || singular.endsWith("ch") || singular.endsWith("sh")) {
            return singular + "es";
        }
        return singular + "s";
    }

    private static String singularize(String word) {
        if (!StringUtils.hasText(word) || word.length() < 3) {
            return word;
        }
        if (word.endsWith("ies") && word.length() > 3) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.endsWith("ses") || word.endsWith("xes")
                || word.endsWith("ches") || word.endsWith("shes")) {
            return word.substring(0, word.length() - 2);
        }
        if (word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }

    private static String extractPrefix(String columnName) {
        if (!StringUtils.hasText(columnName)) {
            return null;
        }
        Matcher m1 = COL_ID.matcher(columnName);
        if (m1.matches()) {
            return m1.group(1);
        }
        Matcher m2 = COL_CAMEL_ID.matcher(columnName);
        if (m2.matches()) {
            return m2.group(1);
        }
        return null;
    }

    private List<TableMeta> loadTables(Connection conn, String datasourceId, String database) throws Exception {
        List<TableMeta> list = new ArrayList<>();
        String sql = "SELECT id, table_name FROM schema_table WHERE datasource_id = ?"
                + (StringUtils.hasText(database) ? " AND db_name = ?" : "")
                + " ORDER BY table_name";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            if (StringUtils.hasText(database)) {
                ps.setString(2, database.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new TableMeta(rs.getString("id"), rs.getString("table_name")));
                }
            }
        }
        return list;
    }

    private Map<String, String> loadPrimaryKeys(Connection conn, String datasourceId, String database) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        String sql = """
                SELECT t.table_name, c.column_name
                FROM schema_table t
                JOIN schema_column c ON c.table_id = t.id
                WHERE t.datasource_id = ? AND c.is_primary_key = 1
                """ + (StringUtils.hasText(database) ? " AND t.db_name = ?" : "") + """
                ORDER BY t.table_name, c.ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            if (StringUtils.hasText(database)) {
                ps.setString(2, database.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.putIfAbsent(rs.getString("table_name"), rs.getString("column_name"));
                }
            }
        }
        return map;
    }

    private List<ColumnMeta> loadColumns(Connection conn, String tableId) throws Exception {
        List<ColumnMeta> list = new ArrayList<>();
        String sql = """
                SELECT id, column_name, is_primary_key, foreign_key_ref, is_inferred
                FROM schema_column
                WHERE table_id = ?
                ORDER BY ordinal_position
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Boolean inferred = null;
                    try {
                        int v = rs.getInt("is_inferred");
                        inferred = rs.wasNull() ? null : v == 1;
                    } catch (Exception ignored) {
                        inferred = null;
                    }
                    list.add(new ColumnMeta(
                            rs.getString("id"),
                            rs.getString("column_name"),
                            rs.getInt("is_primary_key") == 1,
                            rs.getString("foreign_key_ref"),
                            inferred));
                }
            }
        }
        return list;
    }

    private record TableMeta(String id, String tableName) {
    }

    private record ColumnMeta(String id, String columnName, boolean primaryKey,
                              String foreignKeyRef, Boolean inferred) {
    }

    private record Scored(String table, int score) {
    }

    private static final class Decision {
        boolean considered;
        boolean accepted;
        String prefix;
        String ref;
        String skipReason;
        final List<String> layerNotes = new ArrayList<>();
        final List<Map<String, Object>> candidateDetails = new ArrayList<>();

        boolean accepted() {
            return accepted;
        }

        boolean considered() {
            return considered;
        }

        String ref() {
            return ref;
        }

        String skipReason() {
            return skipReason;
        }

        String prefix() {
            return prefix;
        }

        List<String> layerNotes() {
            return layerNotes;
        }

        List<Map<String, Object>> candidateDetails() {
            return candidateDetails;
        }
    }
}
