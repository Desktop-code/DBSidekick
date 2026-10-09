package com.dbsidekick.datasource;

import com.dbsidekick.ai.milvus.MilvusSchemaStore;
import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.relation.RelationUsageService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 运行时动态管理多数据源：SQLite 持久化 + Hikari 连接池。
 */
@Component
public class DynamicDataSourceManager {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceManager.class);

    private final SqliteInitializer sqliteInitializer;
    private final MilvusSchemaStore milvusSchemaStore;
    private final RelationUsageService relationUsageService;
    private final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DbConfig> configs = new ConcurrentHashMap<>();

    public DynamicDataSourceManager(SqliteInitializer sqliteInitializer,
                                    @Lazy MilvusSchemaStore milvusSchemaStore,
                                    @Lazy RelationUsageService relationUsageService) {
        this.sqliteInitializer = sqliteInitializer;
        this.milvusSchemaStore = milvusSchemaStore;
        this.relationUsageService = relationUsageService;
    }

    @PostConstruct
    public void loadFromSqlite() {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, type, host, port, db_name, schema_name, username, password, enabled, "
                             + "db_product, db_version FROM db_connection WHERE enabled = 1");
             ResultSet rs = ps.executeQuery()) {
            int loaded = 0;
            while (rs.next()) {
                DbConfig config = mapRow(rs);
                try {
                    HikariDataSource ds = createPool(config);
                    pools.put(config.getId(), ds);
                    configs.put(config.getId(), config);
                    loaded++;
                } catch (Exception ex) {
                    log.warn("加载数据源失败 id={} name={}: {}", config.getId(), config.getName(), ex.getMessage());
                }
            }
            log.info("从 SQLite 加载数据源 {} 个", loaded);
        } catch (Exception ex) {
            throw new IllegalStateException("从 SQLite 加载数据源失败: " + ex.getMessage(), ex);
        }
    }

    public synchronized String addDataSource(DbConfig config) {
        validateConfig(config);
        String id = StringUtils.hasText(config.getId()) ? config.getId() : UUID.randomUUID().toString();
        if (pools.containsKey(id) || existsInDb(id)) {
            throw new IllegalArgumentException("数据源已存在: " + id);
        }
        config.setId(id);
        if (config.getPassword() == null) {
            config.setPassword("");
        }
        HikariDataSource ds = createPool(config);
        insertDb(config);
        pools.put(id, ds);
        configs.put(id, config);
        return id;
    }

    public synchronized void updateDataSource(DbConfig config) {
        validateConfig(config);
        if (!StringUtils.hasText(config.getId())) {
            throw new IllegalArgumentException("id 不能为空");
        }
        if (!existsInDb(config.getId()) && !configs.containsKey(config.getId())) {
            throw new IllegalArgumentException("数据源不存在: " + config.getId());
        }
        // 密码留空则沿用旧密码（优先内存，其次 SQLite）
        if (!StringUtils.hasText(config.getPassword())) {
            String oldPwd = null;
            try {
                oldPwd = getConfig(config.getId()).getPassword();
            } catch (Exception ignored) {
                // fall through
            }
            if (!StringUtils.hasText(oldPwd)) {
                oldPwd = loadPasswordFromDb(config.getId());
            }
            config.setPassword(oldPwd == null ? "" : oldPwd);
        }
        HikariDataSource newDs = createPool(config);
        updateDb(config);
        HikariDataSource old = pools.put(config.getId(), newDs);
        configs.put(config.getId(), config);
        if (old != null) {
            old.close();
        }
        log.info("[Sidekick] datasource updated: {}", config.getId());
    }

    public synchronized void removeDataSource(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("数据源 id 不能为空");
        }
        String dsId = id.trim();
        // 1) Milvus 向量
        try {
            long deleted = milvusSchemaStore.deleteByDatasource(dsId);
            log.info("[Sidekick] deleted milvus vectors for datasource={} count={}", dsId, deleted);
        } catch (Exception ex) {
            log.warn("[Sidekick] milvus cleanup on delete failed: {}", ex.getMessage());
        }
        // 2) 连接池
        HikariDataSource ds = pools.remove(dsId);
        configs.remove(dsId);
        if (ds != null) {
            ds.close();
        }
        // 3) SQLite schema_* + relation_usage + db_connection（保留 session/script）
        try {
            relationUsageService.deleteByDatasource(dsId);
        } catch (Exception ex) {
            log.warn("[Sidekick] relation_usage cleanup on delete failed: {}", ex.getMessage());
        }
        deleteDbAndSchema(dsId);
    }

    /** 删除前预检：关联数据量。 */
    public Map<String, Object> deleteCheck(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("数据源 id 不能为空");
        }
        String dsId = id.trim();
        if (!configs.containsKey(dsId) && !existsInDb(dsId)) {
            throw new IllegalArgumentException("数据源不存在: " + dsId);
        }
        int schemaTableCount = countSql(
                "SELECT COUNT(*) FROM schema_table WHERE datasource_id = ?", dsId);
        long milvusVectorCount = 0L;
        try {
            milvusVectorCount = milvusSchemaStore.countByDatasource(dsId);
        } catch (Exception ex) {
            log.warn("[Sidekick] delete-check milvus count failed: {}", ex.getMessage());
        }
        int sessionCount = countSql(
                "SELECT COUNT(*) FROM chat_session WHERE datasource_id = ?", dsId);
        int scriptCount = countSql(
                "SELECT COUNT(*) FROM script WHERE datasource_id = ?", dsId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemaTableCount", schemaTableCount);
        resp.put("milvusVectorCount", milvusVectorCount);
        resp.put("sessionCount", sessionCount);
        resp.put("scriptCount", scriptCount);
        String warning;
        if (schemaTableCount == 0 && milvusVectorCount == 0 && sessionCount == 0 && scriptCount == 0) {
            warning = "";
        } else {
            warning = String.format(
                    "将同时删除 %d 张表的结构、%d 条向量索引；%d 个会话和 %d 个脚本将失去关联数据源",
                    schemaTableCount, milvusVectorCount, sessionCount, scriptCount);
        }
        resp.put("warning", warning);
        return resp;
    }

    private int countSql(String sql, String id) {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick] count failed: {}", ex.getMessage());
        }
        return 0;
    }

    public HikariDataSource getDataSource(String id) {
        HikariDataSource ds = pools.get(id);
        if (ds == null) {
            throw new IllegalArgumentException("数据源不存在: " + id);
        }
        return ds;
    }

    /**
     * 按实例连接。目标库与连接池默认库不同时另开一条连接，避免把连接池里的库切走。
     * database 为空时使用连接上保存的默认库（也可为空，表示停在实例入口）。
     */
    public Connection openConnection(String datasourceId, String database) throws SQLException {
        DbConfig config = getConfig(datasourceId);
        String target = StringUtils.hasText(database) ? database.trim() : null;
        String poolDb = StringUtils.hasText(config.getDatabase()) ? config.getDatabase().trim() : "";
        if (config.getType() == DbType.POSTGRESQL && poolDb.isEmpty()) {
            poolDb = "postgres";
        }
        if (!StringUtils.hasText(target) || target.equalsIgnoreCase(poolDb)) {
            return getDataSource(datasourceId).getConnection();
        }
        int port = config.getPort() != null && config.getPort() > 0
                ? config.getPort() : config.getType().getDefaultPort();
        String password = config.getPassword() == null ? "" : config.getPassword();
        if (config.getType() == DbType.POSTGRESQL) {
            String url = "jdbc:postgresql://" + config.getHost() + ":" + port + "/" + target;
            return DriverManager.getConnection(url, config.getUsername(), password);
        }
        String url = "jdbc:mysql://" + config.getHost() + ":" + port + "/" + target
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        return DriverManager.getConnection(url, config.getUsername(), password);
    }

    /** 含密码，供 Schema 同步等内部使用。 */
    public DbConfig getConfig(String id) {
        DbConfig config = configs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("数据源不存在: " + id);
        }
        return config;
    }

    public List<DbConfig> getAllConfigs() {
        List<DbConfig> list = new ArrayList<>();
        for (DbConfig config : configs.values()) {
            list.add(config.maskedCopy());
        }
        return list;
    }

    public Map<String, Object> testConnection(DbConfig config) {
        validateConfig(config);
        // 编辑场景：带 id 且密码留空 → 用已保存密码测连
        if (!StringUtils.hasText(config.getPassword()) && StringUtils.hasText(config.getId())) {
            try {
                String pwd = getConfig(config.getId()).getPassword();
                if (!StringUtils.hasText(pwd)) {
                    pwd = loadPasswordFromDb(config.getId());
                }
                config.setPassword(pwd == null ? "" : pwd);
            } catch (Exception ignored) {
                // 新建测连时 id 可能不存在，保持空密码
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        HikariDataSource temp = null;
        try {
            temp = createPool(config);
            try (Connection conn = temp.getConnection(); Statement st = conn.createStatement()) {
                st.setQueryTimeout(5);
                st.execute("SELECT 1");
            }
            result.put("success", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", "ok");
        } catch (Exception ex) {
            result.put("success", false);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("message", com.dbsidekick.config.UserFacingErrors.of(ex));
            result.put("code", "DB_CONNECT_FAILED");
        } finally {
            if (temp != null) {
                temp.close();
            }
        }
        return result;
    }

    private String loadPasswordFromDb(String id) {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("SELECT password FROM db_connection WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("password");
                }
            }
        } catch (Exception ex) {
            log.debug("[Sidekick] loadPasswordFromDb failed: {}", ex.getMessage());
        }
        return null;
    }

    public synchronized void updateDbVersionMeta(String id, String product, String version) {
        if (!StringUtils.hasText(id)) {
            return;
        }
        String dsId = id.trim();
        DbConfig config = configs.get(dsId);
        if (config != null) {
            config.setDbProduct(product);
            config.setDbVersion(version);
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE db_connection SET db_product=?, db_version=?, updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
            ps.setString(1, product);
            ps.setString(2, version);
            ps.setString(3, dsId);
            ps.executeUpdate();
        } catch (Exception ex) {
            log.warn("[Sidekick] update db version meta failed: {}", ex.getMessage());
        }
    }

    private boolean existsInDb(String id) {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM db_connection WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private void insertDb(DbConfig config) {
        String sql = """
                INSERT INTO db_connection
                (id, name, type, host, port, db_name, schema_name, username, password, enabled,
                 db_product, db_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """;
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bindConfig(ps, config);
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("写入 db_connection 失败: " + ex.getMessage(), ex);
        }
    }

    private void updateDb(DbConfig config) {
        String sql = """
                UPDATE db_connection SET name=?, type=?, host=?, port=?, db_name=?, schema_name=?,
                username=?, password=?, enabled=?, db_product=?, db_version=?,
                updated_at=CURRENT_TIMESTAMP WHERE id=?
                """;
        try (Connection conn = sqliteInitializer.open(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, config.getName());
            ps.setString(2, config.getType().name());
            ps.setString(3, config.getHost());
            ps.setInt(4, config.getPort() != null ? config.getPort() : config.getType().getDefaultPort());
            ps.setString(5, config.getDatabase() == null ? "" : config.getDatabase());
            ps.setString(6, config.getSchema());
            ps.setString(7, config.getUsername());
            ps.setString(8, config.getPassword() == null ? "" : config.getPassword());
            ps.setInt(9, config.isEnabled() ? 1 : 0);
            ps.setString(10, config.getDbProduct());
            ps.setString(11, config.getDbVersion());
            ps.setString(12, config.getId());
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("更新 db_connection 失败: " + ex.getMessage(), ex);
        }
    }

    private void deleteDbAndSchema(String id) {
        try (Connection conn = sqliteInitializer.open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM schema_column WHERE table_id IN (SELECT id FROM schema_table WHERE datasource_id = ?)")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM schema_table WHERE datasource_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM db_connection WHERE id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("删除数据源失败: " + ex.getMessage(), ex);
        }
    }

    private void bindConfig(PreparedStatement ps, DbConfig config) throws Exception {
        ps.setString(1, config.getId());
        ps.setString(2, config.getName());
        ps.setString(3, config.getType().name());
        ps.setString(4, config.getHost());
        ps.setInt(5, config.getPort() != null ? config.getPort() : config.getType().getDefaultPort());
        ps.setString(6, config.getDatabase() == null ? "" : config.getDatabase());
        ps.setString(7, config.getSchema());
        ps.setString(8, config.getUsername());
        ps.setString(9, config.getPassword() == null ? "" : config.getPassword());
        ps.setInt(10, config.isEnabled() ? 1 : 0);
        ps.setString(11, config.getDbProduct());
        ps.setString(12, config.getDbVersion());
    }

    private DbConfig mapRow(ResultSet rs) throws Exception {
        DbConfig config = new DbConfig();
        config.setId(rs.getString("id"));
        config.setName(rs.getString("name"));
        config.setType(DbType.valueOf(rs.getString("type")));
        config.setHost(rs.getString("host"));
        config.setPort(rs.getInt("port"));
        config.setDatabase(rs.getString("db_name"));
        config.setSchema(rs.getString("schema_name"));
        config.setUsername(rs.getString("username"));
        config.setPassword(rs.getString("password"));
        config.setEnabled(rs.getInt("enabled") == 1);
        try {
            config.setDbProduct(rs.getString("db_product"));
            config.setDbVersion(rs.getString("db_version"));
        } catch (Exception ignored) {
            // 老库无列
        }
        return config;
    }

    private HikariDataSource createPool(DbConfig config) {
        DbType type = config.getType();
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("sidekick-" + (StringUtils.hasText(config.getId()) ? config.getId() : "tmp"));
        hc.setDriverClassName(type.getDriverClassName());
        hc.setJdbcUrl(type.buildJdbcUrl(config));
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());
        hc.setMaximumPoolSize(5);
        hc.setConnectionTimeout(5_000);
        hc.setIdleTimeout(60_000);
        hc.setInitializationFailTimeout(5_000);
        return new HikariDataSource(hc);
    }

    private void validateConfig(DbConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        if (config.getType() == null) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (!StringUtils.hasText(config.getHost())) {
            throw new IllegalArgumentException("host 不能为空");
        }
        if (!StringUtils.hasText(config.getUsername())) {
            throw new IllegalArgumentException("username 不能为空");
        }
        if (!StringUtils.hasText(config.getName())) {
            throw new IllegalArgumentException("name 不能为空");
        }
    }
}
