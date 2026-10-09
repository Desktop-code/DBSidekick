package com.dbsidekick.relation;

import com.dbsidekick.config.SidekickProperties;
import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.text2sql.Text2SqlService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用仓库 data/dbsidekick.db 的副本验收迁移、会话去重、INFERRED 加严。
 * 夹具不存在则跳过。不改用户目录里的库。
 */
class RelationFeedbackTest {

    private static final String JOIN_SQL =
            "SELECT u.user_name FROM sys_user u JOIN sys_dept d ON u.dept_id = d.dept_id";

    /** 相对模块目录或仓库根，不写本机绝对路径。 */
    static Path legacyDb() {
        Path fromModule = Path.of("..", "data", "dbsidekick.db");
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        return Path.of("data", "dbsidekick.db");
    }

    static boolean legacyPresent() {
        return Files.isRegularFile(legacyDb());
    }

    @Test
    void promotionThresholdDoublesForInferredWithoutManual() {
        assertEquals(3, RelationUsageService.promotionThreshold("USED", 3, 2));
        assertEquals(6, RelationUsageService.promotionThreshold("INFERRED", 3, 2));
        assertEquals(6, RelationUsageService.promotionThreshold("INFERRED,USED", 3, 2));
        assertEquals(3, RelationUsageService.promotionThreshold("INFERRED,MANUAL", 3, 2));
    }

    @Test
    void questionHashUsesFirstEightHexChars() {
        String hash = Text2SqlService.questionHash("每个部门有哪些人");
        assertEquals(8, hash.length());
        assertEquals(hash, Text2SqlService.questionHash("每个部门有哪些人"));
        assertFalse(hash.equals(Text2SqlService.questionHash("每个部门有哪些人？")));
    }

    @Test
    @EnabledIf("legacyPresent")
    void migratesInferredThenDedupsAndPromotesOnCopy() throws Exception {
        Path testDb = Files.createTempDirectory("dbsidekick-test").resolve("test.db");
        Files.copy(legacyDb(), testDb);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDb.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM relation_usage");
        }

        SqliteInitializer sqlite = new SqliteInitializer(testDb);
        RelationConfigService config = new RelationConfigService(sqlite, new SidekickProperties());
        RelationUsageService usage = new RelationUsageService(sqlite, new JoinExtractor(config), config);
        usage.migrateLegacyInferred();

        try (Connection conn = sqlite.open()) {
            assertEquals(31, count(conn, "SELECT COUNT(*) FROM relation_usage WHERE status='PENDING' AND sources='INFERRED'"));
            assertEquals("true", scalar(conn, "SELECT value FROM app_settings WHERE key='relation_migrated'"));
        }

        String ds;
        try (Connection conn = sqlite.open()) {
            ds = scalar(conn, """
                    SELECT datasource_id FROM relation_usage
                    WHERE source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """);
        }
        assertTrue(ds != null && !ds.isBlank());

        UsageContext ok = new UsageContext(true, 12, false, false);
        usage.recordUsage(ds, "sess-1", "qhash001", JOIN_SQL, ok);
        assertUsage(sqlite, 1, 2, "PENDING");

        usage.recordUsage(ds, "sess-1", "qhash001", JOIN_SQL, ok);
        assertUsage(sqlite, 1, 2, "PENDING");

        usage.recordUsage(ds, "sess-2", "qhash001", JOIN_SQL, ok);
        assertUsage(sqlite, 2, 4, "PENDING");

        usage.recordUsage(ds, "sess-2", "qhash002", JOIN_SQL, ok);
        assertUsage(sqlite, 3, 6, "CONFIRMED");

        String id;
        try (Connection conn = sqlite.open()) {
            id = scalar(conn, """
                    SELECT id FROM relation_usage
                    WHERE source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """);
        }
        usage.markRejected(id);
        usage.recordUsage(ds, "sess-3", "qhash003", JOIN_SQL, ok);
        try (Connection conn = sqlite.open()) {
            assertEquals("REJECTED", scalar(conn, "SELECT status FROM relation_usage WHERE id='" + id + "'"));
            assertEquals(-100, count(conn, "SELECT confidence FROM relation_usage WHERE id='" + id + "'"));
            assertEquals(3, count(conn, "SELECT hit_count FROM relation_usage WHERE id='" + id + "'"));
        }
    }

    private static void assertUsage(SqliteInitializer sqlite, int hits, int confidence, String status) throws Exception {
        try (Connection conn = sqlite.open()) {
            assertEquals(hits, count(conn, """
                    SELECT hit_count FROM relation_usage
                    WHERE source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """));
            assertEquals(confidence, count(conn, """
                    SELECT confidence FROM relation_usage
                    WHERE source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """));
            assertEquals(status, scalar(conn, """
                    SELECT status FROM relation_usage
                    WHERE source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """));
        }
    }

    private static int count(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String scalar(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
