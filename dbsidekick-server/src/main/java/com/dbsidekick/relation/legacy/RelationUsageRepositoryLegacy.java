package com.dbsidekick.relation.legacy;

/**
 * 归档：RelationUsageService 中已迁移到 RelationUsageRepository 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class RelationUsageRepositoryLegacy {

    private RelationUsageRepositoryLegacy() {
    }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：原类声明、字段与三参数构造器（RelationUsageService.java 第 25-45 行）
    // /**
    //  * 隐式反馈关系库：记录 JOIN 使用、晋升 CONFIRMED。
    //  */
    // @Service
    // public class RelationUsageService {
    //
    //     private static final Logger log = LoggerFactory.getLogger(RelationUsageService.class);
    //     private static final DateTimeFormatter TS =
    //             DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    //
    //     private final SqliteInitializer sqliteInitializer;
    //     private final JoinExtractor joinExtractor;
    //     private final RelationConfigService relationConfigService;
    //
    //     public RelationUsageService(SqliteInitializer sqliteInitializer,
    //                                 JoinExtractor joinExtractor,
    //                                 RelationConfigService relationConfigService) {
    //         this.sqliteInitializer = sqliteInitializer;
    //         this.joinExtractor = joinExtractor;
    //         this.relationConfigService = relationConfigService;
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：demoteUsedOnlyConfirmed / bumpThresholdIfOldDefault（RelationUsageService.java 第 89-125 行）
    //     private int demoteUsedOnlyConfirmed(Connection conn) throws Exception {
    //         String sql = """
    //                 UPDATE relation_usage
    //                 SET status = 'PENDING'
    //                 WHERE status = 'CONFIRMED'
    //                   AND (sources IS NULL OR (
    //                     UPPER(',' || REPLACE(IFNULL(sources, ''), ' ', '') || ',') NOT LIKE '%,MANUAL,%'
    //                     AND UPPER(',' || REPLACE(IFNULL(sources, ''), ' ', '') || ',') NOT LIKE '%,SAVED,%'
    //                     AND UPPER(',' || REPLACE(IFNULL(sources, ''), ' ', '') || ',') NOT LIKE '%,LIKED,%'
    //                   ))
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             return ps.executeUpdate();
    //         }
    //     }
    //
    //     private void bumpThresholdIfOldDefault(Connection conn) throws Exception {
    //         try (PreparedStatement ps = conn.prepareStatement(
    //                 "SELECT value FROM relation_config WHERE key = 'confidence-threshold'")) {
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 if (!rs.next()) {
    //                     return;
    //                 }
    //                 String value = rs.getString("value");
    //                 if (!"3".equals(value == null ? "" : value.trim())) {
    //                     return;
    //                 }
    //             }
    //         }
    //         try (PreparedStatement ps = conn.prepareStatement("""
    //                 UPDATE relation_config SET value = '6', updated_at = ?
    //                 WHERE key = 'confidence-threshold'
    //                 """)) {
    //             ps.setString(1, TS.format(Instant.now()));
    //             ps.executeUpdate();
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：upsertInferred / list / count（RelationUsageService.java 第 198-296 行）
    //     /**
    //      * @return true 表示新插入；false 表示已存在未改动
    //      */
    //     public boolean upsertInferred(String datasourceId, RelationPair pair) {
    //         if (!StringUtils.hasText(datasourceId) || pair == null) {
    //             return false;
    //         }
    //         String ds = datasourceId.trim();
    //         try (Connection conn = sqliteInitializer.open()) {
    //             RelationUsage existing = findByPair(conn, ds, pair);
    //             if (existing != null) {
    //                 return false;
    //             }
    //             RelationUsage r = new RelationUsage();
    //             r.setId(UUID.randomUUID().toString());
    //             r.setDatasourceId(ds);
    //             r.setSourceTable(pair.sourceTable());
    //             r.setSourceColumn(pair.sourceColumn());
    //             r.setTargetTable(pair.targetTable());
    //             r.setTargetColumn(pair.targetColumn());
    //             r.setHitCount(0);
    //             r.setConfidence(0);
    //             r.setStatus(RelationUsage.STATUS_PENDING);
    //             r.setSources("INFERRED");
    //             r.setFirstSeenAt(TS.format(Instant.now()));
    //             insertRow(conn, r);
    //             return true;
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("写入推断关系失败: " + ex.getMessage(), ex);
    //         }
    //     }
    //
    //     public List<RelationUsage> list(String datasourceId, String status, String keyword, int page, int size) {
    //         return list(datasourceId, null, status, keyword, page, size);
    //     }
    //
    //     public List<RelationUsage> list(String datasourceId, String database, String status, String keyword,
    //                                     int page, int size) {
    //         if (!StringUtils.hasText(datasourceId)) {
    //             return List.of();
    //         }
    //         int p = Math.max(page, 1);
    //         int sz = Math.min(Math.max(size, 1), 200);
    //         int offset = (p - 1) * sz;
    //         String ds = datasourceId.trim();
    //         StringBuilder sql = new StringBuilder("""
    //                 SELECT id, datasource_id, source_table, source_column, target_table, target_column,
    //                        hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
    //                 FROM relation_usage
    //                 WHERE datasource_id = ?
    //                 """);
    //         appendListFilters(sql, database, status, keyword);
    //         sql.append("""
    //                  ORDER BY confidence DESC,
    //                           CASE WHEN last_used_at IS NULL THEN 1 ELSE 0 END,
    //                           last_used_at DESC, source_table, source_column
    //                  LIMIT ? OFFSET ?
    //                 """);
    //         List<RelationUsage> list = new ArrayList<>();
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql.toString())) {
    //             int i = bindListFilters(ps, 1, ds, database, status, keyword);
    //             ps.setInt(i++, sz);
    //             ps.setInt(i, offset);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     list.add(mapRow(rs));
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("查询关系失败: " + ex.getMessage(), ex);
    //         }
    //         return list;
    //     }
    //
    //     public int count(String datasourceId, String status, String keyword) {
    //         return count(datasourceId, null, status, keyword);
    //     }
    //
    //     public int count(String datasourceId, String database, String status, String keyword) {
    //         if (!StringUtils.hasText(datasourceId)) {
    //             return 0;
    //         }
    //         String ds = datasourceId.trim();
    //         StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM relation_usage WHERE datasource_id = ?");
    //         appendListFilters(sql, database, status, keyword);
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql.toString())) {
    //             bindListFilters(ps, 1, ds, database, status, keyword);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 if (rs.next()) {
    //                     return rs.getInt(1);
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][relation] count failed: {}", ex.getMessage());
    //         }
    //         return 0;
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：loadTableNamesInDatabase / listAllForGraph / 列表过滤（RelationUsageService.java 第 419-515 行）
    //     /** 指定数据源 + 库下的全部表名（来自 schema_table）。 */
    //     private Set<String> loadTableNamesInDatabase(String ds, String database) {
    //         Set<String> out = new LinkedHashSet<>();
    //         String sql = """
    //                 SELECT table_name FROM schema_table
    //                 WHERE datasource_id = ? AND db_name = ?
    //                 """;
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, ds);
    //             ps.setString(2, database);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     String name = rs.getString(1);
    //                     if (StringUtils.hasText(name)) {
    //                         out.add(name.trim());
    //                     }
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("读取 schema_table 失败: " + ex.getMessage(), ex);
    //         }
    //         return out;
    //     }
    //
    //     private List<RelationUsage> listAllForGraph(String ds, String database, String status) {
    //         StringBuilder sql = new StringBuilder("""
    //                 SELECT id, datasource_id, source_table, source_column, target_table, target_column,
    //                        hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
    //                 FROM relation_usage
    //                 WHERE datasource_id = ?
    //                 """);
    //         appendListFilters(sql, database, status, null);
    //         sql.append(" ORDER BY confidence DESC, source_table, source_column");
    //         List<RelationUsage> list = new ArrayList<>();
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql.toString())) {
    //             bindListFilters(ps, 1, ds, database, status, null);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     list.add(mapRow(rs));
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("查询关系图失败: " + ex.getMessage(), ex);
    //         }
    //         return list;
    //     }
    //
    //     private static void appendListFilters(StringBuilder sql, String database, String status, String keyword) {
    //         if (StringUtils.hasText(database)) {
    //             // 两端都必须属于所选库；OR 会导致跨库边把外库表节点带进图
    //             sql.append("""
    //                      AND source_table IN (
    //                        SELECT table_name FROM schema_table
    //                        WHERE datasource_id = ? AND db_name = ?
    //                      )
    //                      AND target_table IN (
    //                        SELECT table_name FROM schema_table
    //                        WHERE datasource_id = ? AND db_name = ?
    //                      )
    //                     """);
    //         }
    //         if (StringUtils.hasText(status)) {
    //             sql.append(" AND status = ?");
    //         }
    //         if (StringUtils.hasText(keyword)) {
    //             sql.append("""
    //                      AND (source_table LIKE ? OR source_column LIKE ?
    //                        OR target_table LIKE ? OR target_column LIKE ?)
    //                     """);
    //         }
    //     }
    //
    //     private static int bindListFilters(PreparedStatement ps, int start, String ds, String database,
    //                                        String status, String keyword) throws Exception {
    //         int i = start;
    //         ps.setString(i++, ds);
    //         if (StringUtils.hasText(database)) {
    //             String db = database.trim();
    //             ps.setString(i++, ds);
    //             ps.setString(i++, db);
    //             ps.setString(i++, ds);
    //             ps.setString(i++, db);
    //         }
    //         if (StringUtils.hasText(status)) {
    //             ps.setString(i++, status.trim().toUpperCase(Locale.ROOT));
    //         }
    //         if (StringUtils.hasText(keyword)) {
    //             String like = "%" + keyword.trim() + "%";
    //             ps.setString(i++, like);
    //             ps.setString(i++, like);
    //             ps.setString(i++, like);
    //             ps.setString(i++, like);
    //         }
    //         return i;
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：loadTableMeta / TableMeta（RelationUsageService.java 第 536-579 行）
    //     private Map<String, TableMeta> loadTableMeta(String ds, String database, Collection<String> tables) {
    //         Map<String, TableMeta> out = new LinkedHashMap<>();
    //         if (tables == null || tables.isEmpty()) {
    //             return out;
    //         }
    //         StringBuilder sql = new StringBuilder("""
    //                 SELECT t.table_name,
    //                        COALESCE(NULLIF(TRIM(t.table_comment), ''), NULLIF(TRIM(t.table_purpose), ''), '') AS comment,
    //                        (SELECT COUNT(*) FROM schema_column c WHERE c.table_id = t.id) AS col_count
    //                 FROM schema_table t
    //                 WHERE t.datasource_id = ?
    //                 """);
    //         if (StringUtils.hasText(database)) {
    //             sql.append(" AND t.db_name = ?");
    //         }
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql.toString())) {
    //             ps.setString(1, ds);
    //             if (StringUtils.hasText(database)) {
    //                 ps.setString(2, database.trim());
    //             }
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     String name = rs.getString("table_name");
    //                     if (!tables.contains(name)) {
    //                         continue;
    //                     }
    //                     out.put(name, new TableMeta(
    //                             name,
    //                             rs.getString("comment") == null ? "" : rs.getString("comment"),
    //                             rs.getInt("col_count")));
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][relation] load table meta failed: {}", ex.getMessage());
    //         }
    //         return out;
    //     }
    //
    //     private record TableMeta(String name, String comment, int columnCount) {
    //         static TableMeta empty(String name) {
    //             return new TableMeta(name, "", 0);
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：listConfirmedTouching / listConfirmedNeighborTables / get / delete / deleteByDatasource（RelationUsageService.java 第 581-701 行）
    //     /**
    //      * 与指定表相关的已确认关系（归一化后源/目标都算），按置信度降序。
    //      */
    //     public List<RelationUsage> listConfirmedTouching(String datasourceId, String tableName) {
    //         if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(tableName)) {
    //             return List.of();
    //         }
    //         String sql = """
    //                 SELECT id, datasource_id, source_table, source_column, target_table, target_column,
    //                        hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
    //                 FROM relation_usage
    //                 WHERE datasource_id = ?
    //                   AND status = 'CONFIRMED'
    //                   AND (LOWER(source_table) = LOWER(?) OR LOWER(target_table) = LOWER(?))
    //                 ORDER BY confidence DESC, source_table, source_column
    //                 """;
    //         List<RelationUsage> list = new ArrayList<>();
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, datasourceId.trim());
    //             ps.setString(2, tableName.trim());
    //             ps.setString(3, tableName.trim());
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     list.add(mapRow(rs));
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][relation] listConfirmedTouching failed: {}", ex.getMessage());
    //         }
    //         return list;
    //     }
    //
    //     /**
    //      * 候选表经由 CONFIRMED 关系连到的邻接表（不含候选表自身）。
    //      */
    //     public Set<String> listConfirmedNeighborTables(String datasourceId, Collection<String> seedTables) {
    //         Set<String> out = new LinkedHashSet<>();
    //         if (!StringUtils.hasText(datasourceId) || seedTables == null || seedTables.isEmpty()) {
    //             return out;
    //         }
    //         Set<String> seeds = new LinkedHashSet<>();
    //         for (String t : seedTables) {
    //             if (StringUtils.hasText(t)) {
    //                 seeds.add(t.trim().toLowerCase(Locale.ROOT));
    //             }
    //         }
    //         if (seeds.isEmpty()) {
    //             return out;
    //         }
    //         String sql = """
    //                 SELECT source_table, target_table
    //                 FROM relation_usage
    //                 WHERE datasource_id = ? AND status = 'CONFIRMED'
    //                 """;
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, datasourceId.trim());
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     String source = rs.getString("source_table");
    //                     String target = rs.getString("target_table");
    //                     boolean sourceHit = source != null && seeds.contains(source.toLowerCase(Locale.ROOT));
    //                     boolean targetHit = target != null && seeds.contains(target.toLowerCase(Locale.ROOT));
    //                     if (sourceHit && target != null && !seeds.contains(target.toLowerCase(Locale.ROOT))) {
    //                         out.add(target);
    //                     }
    //                     if (targetHit && source != null && !seeds.contains(source.toLowerCase(Locale.ROOT))) {
    //                         out.add(source);
    //                     }
    //                 }
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][relation] neighbor lookup failed: {}", ex.getMessage());
    //         }
    //         return out;
    //     }
    //
    //     public RelationUsage get(String id) {
    //         if (!StringUtils.hasText(id)) {
    //             return null;
    //         }
    //         try (Connection conn = sqliteInitializer.open()) {
    //             return getById(conn, id.trim());
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("查询关系失败: " + ex.getMessage(), ex);
    //         }
    //     }
    //
    //     public void delete(String id) {
    //         if (!StringUtils.hasText(id)) {
    //             throw new IllegalArgumentException("id 不能为空");
    //         }
    //         try (Connection conn = sqliteInitializer.open();
    //              PreparedStatement ps = conn.prepareStatement("DELETE FROM relation_usage WHERE id = ?")) {
    //             ps.setString(1, id.trim());
    //             ps.executeUpdate();
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("删除关系失败: " + ex.getMessage(), ex);
    //         }
    //     }
    //
    //     public void deleteByDatasource(String datasourceId) {
    //         if (!StringUtils.hasText(datasourceId)) {
    //             return;
    //         }
    //         try (Connection conn = sqliteInitializer.open()) {
    //             try (PreparedStatement logPs = conn.prepareStatement(
    //                     "DELETE FROM relation_usage_log WHERE relation_id IN (SELECT id FROM relation_usage WHERE datasource_id = ?)")) {
    //                 logPs.setString(1, datasourceId.trim());
    //                 logPs.executeUpdate();
    //             }
    //             try (PreparedStatement ps = conn.prepareStatement("DELETE FROM relation_usage WHERE datasource_id = ?")) {
    //                 ps.setString(1, datasourceId.trim());
    //                 int n = ps.executeUpdate();
    //                 log.info("[Sidekick][relation] deleted {} rows for datasource={}", n, datasourceId);
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][relation] deleteByDatasource failed: {}", ex.getMessage());
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：alreadyScored / writeLog（RelationUsageService.java 第 767-803 行）
    //     private boolean alreadyScored(Connection conn, String relationId, String sessionId, String questionHash)
    //             throws Exception {
    //         if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(questionHash)) {
    //             return false;
    //         }
    //         String sql = """
    //                 SELECT 1 FROM relation_usage_log
    //                 WHERE relation_id = ? AND session_id = ? AND question_hash = ?
    //                 LIMIT 1
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, relationId);
    //             ps.setString(2, sessionId);
    //             ps.setString(3, questionHash);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 return rs.next();
    //             }
    //         }
    //     }
    //
    //     private void writeLog(Connection conn, String relationId, String sessionId, String questionHash,
    //                           int delta, String source) throws Exception {
    //         String sql = """
    //                 INSERT INTO relation_usage_log(id, relation_id, session_id, question_hash, delta, source, created_at)
    //                 VALUES (?, ?, ?, ?, ?, ?, ?)
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, UUID.randomUUID().toString());
    //             ps.setString(2, relationId);
    //             ps.setString(3, sessionId);
    //             ps.setString(4, questionHash);
    //             ps.setInt(5, delta);
    //             ps.setString(6, source);
    //             ps.setString(7, TS.format(Instant.now()));
    //             ps.executeUpdate();
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：countAll / copyInferredRows / settingIsTrue / writeSetting（RelationUsageService.java 第 1057-1130 行）
    //     private int countAll(Connection conn) throws Exception {
    //         try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM relation_usage");
    //              ResultSet rs = ps.executeQuery()) {
    //             return rs.next() ? rs.getInt(1) : 0;
    //         }
    //     }
    //
    //     private int copyInferredRows(Connection conn) throws Exception {
    //         String q = """
    //                 SELECT t.datasource_id, t.table_name, c.column_name, c.foreign_key_ref
    //                 FROM schema_column c
    //                 JOIN schema_table t ON t.id = c.table_id
    //                 WHERE IFNULL(c.is_inferred, 0) = 1
    //                   AND c.foreign_key_ref IS NOT NULL
    //                   AND TRIM(c.foreign_key_ref) <> ''
    //                 """;
    //         int n = 0;
    //         String now = TS.format(Instant.now());
    //         try (PreparedStatement ps = conn.prepareStatement(q);
    //              ResultSet rs = ps.executeQuery()) {
    //             while (rs.next()) {
    //                 String ref = rs.getString("foreign_key_ref");
    //                 int dot = ref == null ? -1 : ref.lastIndexOf('.');
    //                 if (dot <= 0 || dot >= ref.length() - 1) {
    //                     continue;
    //                 }
    //                 RelationPair pair = RelationPair.normalized(
    //                         rs.getString("table_name"),
    //                         rs.getString("column_name"),
    //                         ref.substring(0, dot).trim(),
    //                         ref.substring(dot + 1).trim());
    //                 if (findByPair(conn, rs.getString("datasource_id"), pair) != null) {
    //                     continue;
    //                 }
    //                 RelationUsage r = new RelationUsage();
    //                 r.setId(UUID.randomUUID().toString());
    //                 r.setDatasourceId(rs.getString("datasource_id"));
    //                 r.setSourceTable(pair.sourceTable());
    //                 r.setSourceColumn(pair.sourceColumn());
    //                 r.setTargetTable(pair.targetTable());
    //                 r.setTargetColumn(pair.targetColumn());
    //                 r.setHitCount(0);
    //                 r.setConfidence(0);
    //                 r.setStatus(RelationUsage.STATUS_PENDING);
    //                 r.setSources("INFERRED");
    //                 r.setFirstSeenAt(now);
    //                 insertRow(conn, r);
    //                 n++;
    //             }
    //         }
    //         return n;
    //     }
    //
    //     private boolean settingIsTrue(Connection conn, String key) throws Exception {
    //         try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_settings WHERE key = ?")) {
    //             ps.setString(1, key);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 return rs.next() && "true".equalsIgnoreCase(rs.getString("value"));
    //             }
    //         }
    //     }
    //
    //     private void writeSetting(Connection conn, String key, String value) throws Exception {
    //         String now = TS.format(Instant.now());
    //         try (PreparedStatement ps = conn.prepareStatement("""
    //                 INSERT INTO app_settings(key, value, updated_at) VALUES(?, ?, ?)
    //                 ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
    //                 """)) {
    //             ps.setString(1, key);
    //             ps.setString(2, value);
    //             ps.setString(3, now);
    //             ps.executeUpdate();
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationUsageRepository，保留供对照，确认后由人工删除
    // 原方法：findByPair / getById / insertRow / updateRow / mapRow（RelationUsageService.java 第 1160-1269 行）
    //     private RelationUsage findByPair(Connection conn, String ds, RelationPair pair) throws Exception {
    //         String sql = """
    //                 SELECT id, datasource_id, source_table, source_column, target_table, target_column,
    //                        hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
    //                 FROM relation_usage
    //                 WHERE datasource_id = ?
    //                   AND source_table = ? AND source_column = ?
    //                   AND target_table = ? AND target_column = ?
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, ds);
    //             ps.setString(2, pair.sourceTable());
    //             ps.setString(3, pair.sourceColumn());
    //             ps.setString(4, pair.targetTable());
    //             ps.setString(5, pair.targetColumn());
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 if (rs.next()) {
    //                     return mapRow(rs);
    //                 }
    //             }
    //         }
    //         return null;
    //     }
    //
    //     private RelationUsage getById(Connection conn, String id) throws Exception {
    //         String sql = """
    //                 SELECT id, datasource_id, source_table, source_column, target_table, target_column,
    //                        hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
    //                 FROM relation_usage WHERE id = ?
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, id);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 if (rs.next()) {
    //                     return mapRow(rs);
    //                 }
    //             }
    //         }
    //         return null;
    //     }
    //
    //     private void insertRow(Connection conn, RelationUsage r) throws Exception {
    //         String sql = """
    //                 INSERT INTO relation_usage(
    //                   id, datasource_id, source_table, source_column, target_table, target_column,
    //                   hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows)
    //                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, r.getId());
    //             ps.setString(2, r.getDatasourceId());
    //             ps.setString(3, r.getSourceTable());
    //             ps.setString(4, r.getSourceColumn());
    //             ps.setString(5, r.getTargetTable());
    //             ps.setString(6, r.getTargetColumn());
    //             ps.setInt(7, r.getHitCount());
    //             ps.setInt(8, r.getConfidence());
    //             ps.setString(9, r.getStatus());
    //             ps.setString(10, r.getSources());
    //             ps.setString(11, r.getFirstSeenAt());
    //             ps.setString(12, r.getLastUsedAt());
    //             if (r.getLastUseRows() == null) {
    //                 ps.setNull(13, java.sql.Types.INTEGER);
    //             } else {
    //                 ps.setInt(13, r.getLastUseRows());
    //             }
    //             ps.executeUpdate();
    //         }
    //     }
    //
    //     private void updateRow(Connection conn, RelationUsage r) throws Exception {
    //         String sql = """
    //                 UPDATE relation_usage
    //                 SET hit_count = ?, confidence = ?, status = ?, sources = ?, last_used_at = ?, last_use_rows = ?
    //                 WHERE id = ?
    //                 """;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setInt(1, r.getHitCount());
    //             ps.setInt(2, r.getConfidence());
    //             ps.setString(3, r.getStatus());
    //             ps.setString(4, r.getSources());
    //             ps.setString(5, r.getLastUsedAt());
    //             if (r.getLastUseRows() == null) {
    //                 ps.setNull(6, java.sql.Types.INTEGER);
    //             } else {
    //                 ps.setInt(6, r.getLastUseRows());
    //             }
    //             ps.setString(7, r.getId());
    //             ps.executeUpdate();
    //         }
    //     }
    //
    //     private static RelationUsage mapRow(ResultSet rs) throws Exception {
    //         RelationUsage r = new RelationUsage();
    //         r.setId(rs.getString("id"));
    //         r.setDatasourceId(rs.getString("datasource_id"));
    //         r.setSourceTable(rs.getString("source_table"));
    //         r.setSourceColumn(rs.getString("source_column"));
    //         r.setTargetTable(rs.getString("target_table"));
    //         r.setTargetColumn(rs.getString("target_column"));
    //         r.setHitCount(rs.getInt("hit_count"));
    //         r.setConfidence(rs.getInt("confidence"));
    //         r.setStatus(rs.getString("status"));
    //         r.setSources(rs.getString("sources"));
    //         r.setFirstSeenAt(rs.getString("first_seen_at"));
    //         r.setLastUsedAt(rs.getString("last_used_at"));
    //         int rows = rs.getInt("last_use_rows");
    //         r.setLastUseRows(rs.wasNull() ? null : rows);
    //         return r;
    //     }

}
