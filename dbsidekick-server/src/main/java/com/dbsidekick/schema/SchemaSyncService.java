package com.dbsidekick.schema;

import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DbVersionInfo;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SchemaSyncService {

    private static final Logger log = LoggerFactory.getLogger(SchemaSyncService.class);
    private static final long VERSION_CACHE_TTL_MS = 5 * 60 * 1000L;

    private final DynamicDataSourceManager dataSourceManager;
    private final SchemaExtractorFactory extractorFactory;
    private final SqliteInitializer sqliteInitializer;
    private final RelationInferService relationInferService;
    private final ConcurrentHashMap<String, CachedVersion> versionCache = new ConcurrentHashMap<>();

    public SchemaSyncService(DynamicDataSourceManager dataSourceManager,
                             SchemaExtractorFactory extractorFactory,
                             SqliteInitializer sqliteInitializer,
                             RelationInferService relationInferService) {
        this.dataSourceManager = dataSourceManager;
        this.extractorFactory = extractorFactory;
        this.sqliteInitializer = sqliteInitializer;
        this.relationInferService = relationInferService;
    }

    public SyncResult sync(String datasourceId) throws Exception {
        return sync(datasourceId, null);
    }

    public SyncResult sync(String datasourceId, String database) throws Exception {
        long start = System.currentTimeMillis();
        DbConfig config = dataSourceManager.getConfig(datasourceId);
        String db = StringUtils.hasText(database) ? database.trim()
                : (StringUtils.hasText(config.getDatabase()) ? config.getDatabase().trim() : null);
        if (!StringUtils.hasText(db)) {
            throw new IllegalArgumentException("请先选择要同步的数据库");
        }

        // 抓取并落库数据库版本
        try {
            refreshAndPersistVersion(datasourceId);
        } catch (Exception ex) {
            log.warn("[Sidekick] sync read db version failed: {}", ex.getMessage());
        }

        List<SchemaTable> tables = extractorFactory.get(config.getType()).extract(config, db);

        int tableCount = 0;
        int columnCount = 0;
        try (Connection conn = sqliteInitializer.open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM schema_column WHERE table_id IN (SELECT id FROM schema_table WHERE datasource_id = ? AND db_name = ?)")) {
                    ps.setString(1, datasourceId);
                    ps.setString(2, db);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM schema_table WHERE datasource_id = ? AND db_name = ?")) {
                    ps.setString(1, datasourceId);
                    ps.setString(2, db);
                    ps.executeUpdate();
                }

                String insertTable = """
                        INSERT INTO schema_table (id, datasource_id, db_name, table_name, table_comment, ddl_text,
                          table_aliases, table_purpose, synced_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP)
                        """;
                String insertColumn = """
                        INSERT INTO schema_column
                        (id, table_id, column_name, data_type, column_comment, is_primary_key, is_nullable,
                         foreign_key_ref, is_inferred, ordinal_position)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                        """;
                try (PreparedStatement tablePs = conn.prepareStatement(insertTable);
                     PreparedStatement colPs = conn.prepareStatement(insertColumn)) {
                    for (SchemaTable table : tables) {
                        String tableId = UUID.randomUUID().toString();
                        tablePs.setString(1, tableId);
                        tablePs.setString(2, datasourceId);
                        tablePs.setString(3, db);
                        tablePs.setString(4, table.getTableName());
                        tablePs.setString(5, table.getTableComment());
                        tablePs.setString(6, table.toCardText());
                        tablePs.executeUpdate();
                        tableCount++;

                        for (SchemaColumn col : table.getColumns()) {
                            colPs.setString(1, UUID.randomUUID().toString());
                            colPs.setString(2, tableId);
                            colPs.setString(3, col.getColumnName());
                            colPs.setString(4, col.getDataType());
                            colPs.setString(5, col.getColumnComment());
                            colPs.setInt(6, col.isPrimaryKey() ? 1 : 0);
                            colPs.setInt(7, col.isNullable() ? 1 : 0);
                            colPs.setString(8, col.getForeignKeyRef());
                            colPs.setInt(9, col.getOrdinalPosition());
                            colPs.executeUpdate();
                            columnCount++;
                        }
                    }
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }

        // 同步完成后自动推断外键（失败不阻断同步结果）
        try {
            InferReport inferReport = relationInferService.infer(datasourceId, db);
            log.info("[Sidekick] sync auto-infer inferred={} existing={} elapsedMs={}",
                    inferReport.inferredCount(), inferReport.existingCount(), inferReport.elapsedMs());
        } catch (Exception ex) {
            log.warn("[Sidekick] sync auto-infer skipped: {}", ex.getMessage());
        }

        return new SyncResult(tableCount, columnCount, System.currentTimeMillis() - start);
    }

    public List<String> listDatabases(String datasourceId) throws Exception {
        DbConfig config = dataSourceManager.getConfig(datasourceId);
        List<String> names = new ArrayList<>();
        try (Connection conn = dataSourceManager.openConnection(datasourceId, null)) {
            if (config.getType() == com.dbsidekick.datasource.DbType.POSTGRESQL) {
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT datname FROM pg_database
                        WHERE datistemplate = false AND datallowconn = true
                        ORDER BY datname
                        """);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT SCHEMA_NAME FROM information_schema.SCHEMATA
                        WHERE SCHEMA_NAME NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')
                        ORDER BY SCHEMA_NAME
                        """);
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
        }
        return names;
    }

    public List<Map<String, Object>> listTables(String datasourceId) throws Exception {
        return listTables(datasourceId, null);
    }

    public List<Map<String, Object>> listTables(String datasourceId, String database) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = """
                SELECT id, table_name, table_comment, db_name, synced_at
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
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("tableName", rs.getString("table_name"));
                    row.put("tableComment", rs.getString("table_comment"));
                    row.put("dbName", rs.getString("db_name"));
                    row.put("syncedAt", rs.getString("synced_at"));
                    list.add(row);
                }
            }
        }
        return list;
    }

    public Map<String, Object> getTableDetail(String tableId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Connection conn = sqliteInitializer.open()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, datasource_id, db_name, table_name, table_comment, ddl_text, synced_at
                    FROM schema_table WHERE id = ?
                    """)) {
                ps.setString(1, tableId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("表不存在: " + tableId);
                    }
                    result.put("id", rs.getString("id"));
                    result.put("datasourceId", rs.getString("datasource_id"));
                    result.put("dbName", rs.getString("db_name"));
                    result.put("tableName", rs.getString("table_name"));
                    result.put("tableComment", rs.getString("table_comment"));
                    result.put("ddlText", rs.getString("ddl_text"));
                    result.put("syncedAt", rs.getString("synced_at"));
                }
            }

            List<Map<String, Object>> columns = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, column_name, data_type, column_comment, is_primary_key, is_nullable,
                           foreign_key_ref, ordinal_position
                    FROM schema_column
                    WHERE table_id = ?
                    ORDER BY ordinal_position
                    """)) {
                ps.setString(1, tableId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("id", rs.getString("id"));
                        col.put("columnName", rs.getString("column_name"));
                        col.put("dataType", rs.getString("data_type"));
                        col.put("columnComment", rs.getString("column_comment"));
                        col.put("primaryKey", rs.getInt("is_primary_key") == 1);
                        col.put("nullable", rs.getInt("is_nullable") == 1);
                        col.put("foreignKeyRef", rs.getString("foreign_key_ref"));
                        col.put("ordinalPosition", rs.getInt("ordinal_position"));
                        columns.add(col);
                    }
                }
            }
            result.put("columns", columns);
        }
        return result;
    }

    /**
     * 读取数据库版本（5 分钟缓存）。
     */
    public DbVersionInfo readVersion(String datasourceId) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String ds = datasourceId.trim();
        CachedVersion cached = versionCache.get(ds);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.atMs < VERSION_CACHE_TTL_MS) {
            return cached.info;
        }
        try {
            return refreshAndPersistVersion(ds);
        } catch (Exception ex) {
            // 回退到已落库的元数据
            try {
                DbConfig cfg = dataSourceManager.getConfig(ds);
                if (StringUtils.hasText(cfg.getDbProduct()) || StringUtils.hasText(cfg.getDbVersion())) {
                    DbVersionInfo info = DbVersionInfo.of(cfg.getDbProduct(), cfg.getDbVersion());
                    versionCache.put(ds, new CachedVersion(info, now));
                    return info;
                }
                return DbVersionInfo.unknown(cfg.getType());
            } catch (Exception ignored) {
                throw new IllegalStateException("读取数据库版本失败: " + ex.getMessage(), ex);
            }
        }
    }

    public DbVersionInfo refreshAndPersistVersion(String datasourceId) throws Exception {
        String ds = datasourceId.trim();
        HikariDataSource hikari = dataSourceManager.getDataSource(ds);
        String product;
        String version;
        try (Connection conn = hikari.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            product = meta.getDatabaseProductName();
            version = meta.getDatabaseProductVersion();
        }
        dataSourceManager.updateDbVersionMeta(ds, product, version);
        DbVersionInfo info = DbVersionInfo.of(product, version);
        versionCache.put(ds, new CachedVersion(info, System.currentTimeMillis()));
        log.info("[Sidekick] db version datasource={} product={} version={}", ds, product, version);
        return info;
    }

    private record CachedVersion(DbVersionInfo info, long atMs) {
    }

    public record SyncResult(int tableCount, int columnCount, long elapsedMs) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tableCount", tableCount);
            map.put("columnCount", columnCount);
            map.put("elapsedMs", elapsedMs);
            return map;
        }
    }
}
