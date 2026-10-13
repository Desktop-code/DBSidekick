package com.dbsidekick.relation;

import com.dbsidekick.config.SidekickProperties;
import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.text2sql.Text2SqlService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 置信度 v2：强信号晋级、👎 减分、连续 👎 拒绝。
 * 夹具不存在则跳过依赖真实库的用例。
 */
class RelationFeedbackTest {

    private static final String JOIN_SQL =
            "SELECT u.user_name FROM sys_user u JOIN sys_dept d ON u.dept_id = d.dept_id";

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
    void promotionThresholdDoublesForInferredWithoutStrongSignal() {
        assertEquals(6, RelationUsageService.promotionThreshold("USED", 6, 2));
        assertEquals(12, RelationUsageService.promotionThreshold("INFERRED", 6, 2));
        assertEquals(12, RelationUsageService.promotionThreshold("INFERRED,USED", 6, 2));
        assertEquals(6, RelationUsageService.promotionThreshold("INFERRED,LIKED", 6, 2));
        assertEquals(6, RelationUsageService.promotionThreshold("INFERRED,MANUAL", 6, 2));
    }

    @Test
    void strongSignalDetectsManualSavedLiked() {
        assertTrue(RelationUsageService.hasStrongSignal("USED,LIKED"));
        assertTrue(RelationUsageService.hasStrongSignal("SAVED"));
        assertTrue(RelationUsageService.hasStrongSignal("MANUAL"));
        assertFalse(RelationUsageService.hasStrongSignal("USED"));
        assertFalse(RelationUsageService.hasStrongSignal("INFERRED,USED"));
    }

    @Test
    void displayKindMarksAutoWhenConfirmedWithoutStrongSignal() {
        RelationUsage auto = new RelationUsage();
        auto.setStatus("CONFIRMED");
        auto.setSources("USED");
        assertEquals("AUTO", RelationUsageService.displayKind(auto));

        RelationUsage confirmed = new RelationUsage();
        confirmed.setStatus("CONFIRMED");
        confirmed.setSources("USED,LIKED");
        assertEquals("CONFIRMED", RelationUsageService.displayKind(confirmed));
    }

    @Test
    void questionHashUsesFirstEightHexChars() {
        String hash = Text2SqlService.questionHash("每个部门有哪些人");
        assertEquals(8, hash.length());
        assertEquals(hash, Text2SqlService.questionHash("每个部门有哪些人"));
        assertFalse(hash.equals(Text2SqlService.questionHash("每个部门有哪些人？")));
    }

    @Test
    void usedOnlyCannotPromoteWithoutStrongSignal() throws Exception {
        Path dir = Files.createTempDirectory("dbsidekick-rel-v2");
        Path testDb = dir.resolve("test.db");
        SqliteInitializer sqlite = new SqliteInitializer(testDb);
        RelationConfigService config = new RelationConfigService(sqlite, new SidekickProperties());
        config.seedMissing();
        config.set("confidence-threshold", "6");
        config.set("require-strong-signal", "true");
        RelationUsageService usage = new RelationUsageService(sqlite, new JoinExtractor(config), config);

        String ds = "ds-test";
        UsageContext ok = new UsageContext(true, 12, false, false);
        for (int i = 0; i < 3; i++) {
            usage.recordUsage(ds, "sess-" + i, "qh" + i, JOIN_SQL, ok);
        }
        // 3 次成功 = 6 分，无强信号，不得晋级
        assertUsage(sqlite, ds, 3, 6, "PENDING");
    }

    @Test
    void likeProvidesStrongSignalAndPromotes() throws Exception {
        Path dir = Files.createTempDirectory("dbsidekick-rel-like");
        Path testDb = dir.resolve("test.db");
        SqliteInitializer sqlite = new SqliteInitializer(testDb);
        RelationConfigService config = new RelationConfigService(sqlite, new SidekickProperties());
        config.seedMissing();
        config.set("confidence-threshold", "6");
        config.set("require-strong-signal", "true");
        RelationUsageService usage = new RelationUsageService(sqlite, new JoinExtractor(config), config);

        String ds = "ds-like";
        seedPair(sqlite, ds);
        UsageContext ok = new UsageContext(true, 5, false, false);
        usage.recordUsage(ds, "s1", "q1", JOIN_SQL, ok);
        assertUsage(sqlite, ds, 1, 2, "PENDING");

        usage.recordFeedback(ds, "s1", "m1", JOIN_SQL, true);
        assertUsage(sqlite, ds, 1, 5, "PENDING");

        usage.recordUsage(ds, "s2", "q2", JOIN_SQL, ok);
        assertUsage(sqlite, ds, 2, 7, "CONFIRMED");
    }

    @Test
    void threeDislikesForceRejected() throws Exception {
        Path dir = Files.createTempDirectory("dbsidekick-rel-dislike");
        Path testDb = dir.resolve("test.db");
        SqliteInitializer sqlite = new SqliteInitializer(testDb);
        RelationConfigService config = new RelationConfigService(sqlite, new SidekickProperties());
        config.seedMissing();
        config.set("max-consecutive-dislikes", "3");
        RelationUsageService usage = new RelationUsageService(sqlite, new JoinExtractor(config), config);

        String ds = "ds-dislike";
        seedPair(sqlite, ds);
        usage.recordUsage(ds, "s1", "q1", JOIN_SQL, new UsageContext(true, 3, false, false));
        usage.recordFeedback(ds, "s1", "m1", JOIN_SQL, false);
        usage.recordFeedback(ds, "s1", "m2", JOIN_SQL, false);
        assertUsage(sqlite, ds, 1, -8, "PENDING");
        usage.recordFeedback(ds, "s1", "m3", JOIN_SQL, false);
        assertUsage(sqlite, ds, 1, -100, "REJECTED");
    }

    @Test
    @EnabledIf("legacyPresent")
    void migratesInferredThenDedupsOnCopy() throws Exception {
        Path testDb = Files.createTempDirectory("dbsidekick-test").resolve("test.db");
        Files.copy(legacyDb(), testDb);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + testDb.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("DELETE FROM relation_usage");
            st.execute("DELETE FROM app_settings WHERE key IN ('relation_migrated','relation_v2_migrated')");
        }

        SqliteInitializer sqlite = new SqliteInitializer(testDb);
        RelationConfigService config = new RelationConfigService(sqlite, new SidekickProperties());
        RelationUsageService usage = new RelationUsageService(sqlite, new JoinExtractor(config), config);
        usage.migrateLegacyInferred();

        try (Connection conn = sqlite.open()) {
            assertEquals(31, count(conn, "SELECT COUNT(*) FROM relation_usage WHERE status='PENDING' AND sources='INFERRED'"));
            assertEquals("true", scalar(conn, "SELECT value FROM app_settings WHERE key='relation_migrated'"));
            assertEquals("true", scalar(conn, "SELECT value FROM app_settings WHERE key='relation_v2_migrated'"));
        }
    }

    private static void seedPair(SqliteInitializer sqlite, String ds) throws Exception {
        try (Connection conn = sqlite.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO relation_usage(
                       id, datasource_id, source_table, source_column, target_table, target_column,
                       hit_count, confidence, status, sources, first_seen_at)
                     VALUES (?, ?, 'sys_dept', 'dept_id', 'sys_user', 'dept_id', 0, 0, 'PENDING', 'INFERRED', datetime('now'))
                     """)) {
            ps.setString(1, java.util.UUID.randomUUID().toString());
            ps.setString(2, ds);
            ps.executeUpdate();
        }
    }

    private static void assertUsage(SqliteInitializer sqlite, String ds, int hits, int confidence, String status)
            throws Exception {
        try (Connection conn = sqlite.open()) {
            assertEquals(hits, count(conn, """
                    SELECT hit_count FROM relation_usage
                    WHERE datasource_id='%s' AND source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """.formatted(ds)));
            assertEquals(confidence, count(conn, """
                    SELECT confidence FROM relation_usage
                    WHERE datasource_id='%s' AND source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """.formatted(ds)));
            assertEquals(status, scalar(conn, """
                    SELECT status FROM relation_usage
                    WHERE datasource_id='%s' AND source_table='sys_dept' AND source_column='dept_id'
                      AND target_table='sys_user' AND target_column='dept_id'
                    """.formatted(ds)));
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
