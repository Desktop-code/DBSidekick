package com.dbsidekick.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 本地 SQLite：用户目录落库，按 schema_version 迁移。
 */
@Component
public class SqliteInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqliteInitializer.class);
    private static final int CURRENT_SCHEMA_VERSION = 2;

    public static final Path DATA_DIR = Paths.get(System.getProperty("user.home"), ".dbsidekick");
    public static final Path DB_PATH = DATA_DIR.resolve("dbsidekick.db");

    private final Object gate = new Object();
    private final Path dbPath;
    private volatile boolean maintenance;

    @Autowired
    public SqliteInitializer() {
        this(DB_PATH);
    }

    /** 测试用：对指定文件做同样的版本迁移，不改用户目录里的库。 */
    public SqliteInitializer(Path dbPath) {
        this.dbPath = dbPath == null ? DB_PATH : dbPath;
        init();
    }

    public static String jdbcUrl() {
        return "jdbc:sqlite:" + DB_PATH.toAbsolutePath();
    }

    public Connection open() throws Exception {
        synchronized (gate) {
            if (maintenance) {
                throw new IllegalStateException("数据库正在维护，请重启应用");
            }
        }
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
    }

    public Path dataDir() {
        return DATA_DIR;
    }

    public long dbSize() {
        try {
            return Files.exists(DB_PATH) ? Files.size(DB_PATH) : 0L;
        } catch (Exception ex) {
            return 0L;
        }
    }

    /**
     * 在线备份。SQLite 3.27+ 的 VACUUM INTO 不要求先关掉业务连接。
     */
    public void backupTo(Path target) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("targetPath 不能为空");
        }
        Path dest = target.toAbsolutePath().normalize();
        if (dest.equals(DB_PATH.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("不能把数据库备份覆盖到自身");
        }
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        if (Files.exists(dest)) {
            Files.delete(dest);
        }
        String sqlPath = dest.toString().replace('\\', '/').replace("'", "''");
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("VACUUM INTO '" + sqlPath + "'");
        }
    }

    public void restoreFrom(Path source) throws Exception {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("备份文件不存在");
        }
        assertHealthySqlite(source);
        Path bak = DATA_DIR.resolve("dbsidekick.db.bak");
        synchronized (gate) {
            maintenance = true;
        }
        try {
            checkpointQuietly();
            Files.createDirectories(DATA_DIR);
            if (Files.exists(DB_PATH)) {
                Files.copy(DB_PATH, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(source, DB_PATH, StandardCopyOption.REPLACE_EXISTING);
            deleteIfExists(Path.of(DB_PATH.toString() + "-wal"));
            deleteIfExists(Path.of(DB_PATH.toString() + "-shm"));
        } finally {
            synchronized (gate) {
                maintenance = false;
            }
        }
    }

    public void resetDatabase() throws Exception {
        synchronized (gate) {
            maintenance = true;
        }
        try {
            checkpointQuietly();
            deleteIfExists(DB_PATH);
            deleteIfExists(Path.of(DB_PATH.toString() + "-wal"));
            deleteIfExists(Path.of(DB_PATH.toString() + "-shm"));
        } finally {
            synchronized (gate) {
                maintenance = false;
            }
        }
    }

    private void init() {
        try {
            Files.createDirectories(dbPath.getParent() == null ? DATA_DIR : dbPath.getParent());
            if (dbPath.equals(DB_PATH)) {
                noteLegacyDatabases();
            }
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
                conn.setAutoCommit(false);
                migrate(conn);
                conn.commit();
            }
            log.info("[Sidekick] SQLite ready at {}", dbPath.toAbsolutePath());
        } catch (Exception ex) {
            throw new IllegalStateException("初始化 SQLite 失败: " + ex.getMessage(), ex);
        }
    }

    private void noteLegacyDatabases() {
        Path cwdLegacy = Paths.get("data", "dbsidekick.db").toAbsolutePath();
        if (Files.exists(cwdLegacy) && !Files.exists(DB_PATH)) {
            log.info("[Sidekick] legacy db found at {}, ignoring (new path: {})",
                    cwdLegacy, DB_PATH.toAbsolutePath());
        }
    }

    private void migrate(Connection conn) throws SQLException {
        exec(conn, "CREATE TABLE IF NOT EXISTS schema_info (key TEXT PRIMARY KEY, value TEXT)");
        int current = getInt(conn, "schema_version", 0);
        if (current < 1) {
            applyV1(conn);
            setInt(conn, "schema_version", 1);
            log.info("[Sidekick] migrated SQLite to v1");
            current = 1;
        }
        if (current < 2) {
            applyV2(conn);
            setInt(conn, "schema_version", 2);
            log.info("[Sidekick] migrated SQLite to v2");
        } else if (current > CURRENT_SCHEMA_VERSION) {
            log.warn("[Sidekick] SQLite schema_version={} is newer than this build ({})",
                    current, CURRENT_SCHEMA_VERSION);
        } else {
            log.info("[Sidekick] SQLite schema_version={}, skip migration", current);
        }
    }

    private void applyV1(Connection conn) throws SQLException {
        exec(conn, """
                CREATE TABLE IF NOT EXISTS db_connection (
                  id            TEXT PRIMARY KEY,
                  name          TEXT NOT NULL,
                  type          TEXT NOT NULL,
                  host          TEXT NOT NULL,
                  port          INTEGER NOT NULL,
                  db_name       TEXT NOT NULL,
                  schema_name   TEXT,
                  username      TEXT NOT NULL,
                  password      TEXT NOT NULL,
                  enabled       INTEGER DEFAULT 1,
                  db_product    TEXT,
                  db_version    TEXT,
                  created_at    TEXT DEFAULT CURRENT_TIMESTAMP,
                  updated_at    TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        tryAlter(conn, "ALTER TABLE db_connection ADD COLUMN db_product TEXT");
        tryAlter(conn, "ALTER TABLE db_connection ADD COLUMN db_version TEXT");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS schema_table (
                  id             TEXT PRIMARY KEY,
                  datasource_id  TEXT NOT NULL,
                  db_name        TEXT NOT NULL,
                  table_name     TEXT NOT NULL,
                  table_comment  TEXT,
                  ddl_text       TEXT,
                  table_aliases  TEXT,
                  table_purpose  TEXT,
                  synced_at      TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_schema_table_ds ON schema_table(datasource_id)");
        tryAlter(conn, "ALTER TABLE schema_table ADD COLUMN table_aliases TEXT");
        tryAlter(conn, "ALTER TABLE schema_table ADD COLUMN table_purpose TEXT");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS schema_column (
                  id                TEXT PRIMARY KEY,
                  table_id          TEXT NOT NULL,
                  column_name       TEXT NOT NULL,
                  data_type         TEXT,
                  column_comment    TEXT,
                  is_primary_key    INTEGER DEFAULT 0,
                  is_nullable       INTEGER DEFAULT 1,
                  foreign_key_ref   TEXT,
                  is_inferred       INTEGER DEFAULT 0,
                  ordinal_position  INTEGER
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_schema_column_table ON schema_column(table_id)");
        tryAlter(conn, "ALTER TABLE schema_column ADD COLUMN is_inferred INTEGER DEFAULT 0");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS few_shot_example (
                  id             TEXT PRIMARY KEY,
                  datasource_id  TEXT NOT NULL,
                  question       TEXT NOT NULL,
                  sql_text       TEXT NOT NULL,
                  tables_used    TEXT,
                  hit_count      INTEGER DEFAULT 0,
                  last_used_at   TEXT,
                  created_at     TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_fewshot_ds ON few_shot_example(datasource_id)");
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_fewshot_tables ON few_shot_example(tables_used)");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS script (
                  id             TEXT PRIMARY KEY,
                  name           TEXT NOT NULL,
                  content        TEXT NOT NULL,
                  datasource_id  TEXT,
                  db_name        TEXT,
                  source         TEXT DEFAULT 'MANUAL',
                  favorited      INTEGER DEFAULT 0,
                  created_at     TEXT DEFAULT CURRENT_TIMESTAMP,
                  updated_at     TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_script_fav ON script(favorited)");
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_script_updated ON script(updated_at DESC)");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS app_settings (
                  key         TEXT PRIMARY KEY,
                  value       TEXT,
                  updated_at  TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, """
                CREATE TABLE IF NOT EXISTS chat_session (
                  id             TEXT PRIMARY KEY,
                  title          TEXT NOT NULL,
                  datasource_id  TEXT NOT NULL,
                  db_name        TEXT,
                  created_at     TEXT DEFAULT CURRENT_TIMESTAMP,
                  updated_at     TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_chat_session_updated ON chat_session(updated_at DESC)");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS chat_message (
                  id             TEXT PRIMARY KEY,
                  session_id     TEXT NOT NULL,
                  role           TEXT NOT NULL,
                  content        TEXT NOT NULL,
                  sql_text       TEXT,
                  sql_status     TEXT,
                  result_json    TEXT,
                  reasoning_json TEXT,
                  created_at     TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_chat_message_session ON chat_message(session_id, created_at)");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS relation_usage (
                  id             TEXT PRIMARY KEY,
                  datasource_id  TEXT NOT NULL,
                  source_table   TEXT NOT NULL,
                  source_column  TEXT NOT NULL,
                  target_table   TEXT NOT NULL,
                  target_column  TEXT NOT NULL,
                  hit_count      INTEGER DEFAULT 0,
                  confidence     INTEGER DEFAULT 0,
                  status         TEXT DEFAULT 'PENDING',
                  sources        TEXT,
                  first_seen_at  TEXT DEFAULT CURRENT_TIMESTAMP,
                  last_used_at   TEXT,
                  last_use_rows  INTEGER,
                  UNIQUE(datasource_id, source_table, source_column, target_table, target_column)
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_relation_ds ON relation_usage(datasource_id)");
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_relation_status ON relation_usage(datasource_id, status)");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS relation_config (
                  key         TEXT PRIMARY KEY,
                  value       TEXT,
                  updated_at  TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void applyV2(Connection conn) throws SQLException {
        tryAlter(conn, "ALTER TABLE relation_usage ADD COLUMN last_use_rows INTEGER");
        exec(conn, """
                CREATE TABLE IF NOT EXISTS relation_usage_log (
                  id             TEXT PRIMARY KEY,
                  relation_id    TEXT NOT NULL,
                  session_id     TEXT,
                  question_hash  TEXT,
                  delta          INTEGER,
                  source         TEXT,
                  created_at     TEXT DEFAULT CURRENT_TIMESTAMP
                )
                """);
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_relation_log_relation ON relation_usage_log(relation_id)");
        exec(conn, "CREATE INDEX IF NOT EXISTS idx_relation_log_session ON relation_usage_log(session_id, question_hash)");
        exec(conn, """
                INSERT OR IGNORE INTO relation_config(key, value) VALUES
                  ('confidence-threshold', '3'),
                  ('score.sql-success', '1'),
                  ('score.row-count-positive', '1'),
                  ('score.saved-to-script', '5'),
                  ('score.user-confirmed', '10'),
                  ('score.user-rejected', '-100'),
                  ('excluded-column-names', 'id,uuid,guid,code,no,num,sort,order,seq,version'),
                  ('temp-table-prefixes', 'tmp_,temp_,_tmp_,_temp_'),
                  ('adjacency-whitelist-limit', '8'),
                  ('inferred-threshold-multiplier', '2')
                """);
    }

    private static void assertHealthySqlite(Path source) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new IllegalArgumentException("备份文件未通过完整性检查");
            }
        } catch (SQLException ex) {
            throw new IllegalArgumentException("不是有效的 SQLite 备份: " + ex.getMessage(), ex);
        }
    }

    private void checkpointQuietly() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl());
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (Exception ex) {
            log.debug("[Sidekick] checkpoint skip: {}", ex.getMessage());
        }
    }

    private static void deleteIfExists(Path path) throws Exception {
        Files.deleteIfExists(path);
    }

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static void tryAlter(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (Exception ex) {
            log.debug("ALTER skip: {} ({})", sql, ex.getMessage());
        }
    }

    private static int getInt(Connection conn, String key, int fallback) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT value FROM schema_info WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString("value"));
                }
            }
        } catch (NumberFormatException ex) {
            return fallback;
        }
        return fallback;
    }

    private static void setInt(Connection conn, String key, int value) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO schema_info(key, value) VALUES(?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            ps.setString(1, key);
            ps.setString(2, String.valueOf(value));
            ps.executeUpdate();
        }
    }
}
