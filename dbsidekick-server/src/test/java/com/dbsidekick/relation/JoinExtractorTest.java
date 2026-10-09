package com.dbsidekick.relation;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinExtractorTest {

    private static final Set<String> EXCLUDED = Set.of(
            "id", "uuid", "guid", "code", "no", "num", "sort", "order", "seq", "version");
    private static final List<String> TEMP = List.of("tmp_", "temp_", "_tmp_", "_temp_");

    private JoinExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new JoinExtractor(null);
    }

    @Test
    void extractsAliasJoinAndNormalizesDirection() {
        List<RelationPair> a = extractor.extract(
                "SELECT * FROM sys_user u JOIN sys_dept d ON u.dept_id = d.dept_id",
                EXCLUDED, TEMP);
        List<RelationPair> b = extractor.extract(
                "SELECT * FROM sys_dept d JOIN sys_user u ON d.dept_id = u.dept_id",
                EXCLUDED, TEMP);
        assertEquals(1, a.size());
        assertEquals(a, b);
        RelationPair pair = a.get(0);
        assertEquals("sys_dept", pair.sourceTable());
        assertEquals("dept_id", pair.sourceColumn());
        assertEquals("sys_user", pair.targetTable());
        assertEquals("dept_id", pair.targetColumn());
    }

    @Test
    void skipsSameTableConstantGenericAndTemp() {
        assertTrue(extractor.extract(
                "SELECT * FROM sys_user u WHERE u.dept_id = u.org_id", EXCLUDED, TEMP).isEmpty());
        assertTrue(extractor.extract(
                "SELECT * FROM sys_user u JOIN sys_dept d ON u.dept_id = 1", EXCLUDED, TEMP).isEmpty());
        assertTrue(extractor.extract(
                "SELECT * FROM sys_user u JOIN sys_dept d ON u.id = d.dept_id", EXCLUDED, TEMP).isEmpty());
        assertTrue(extractor.extract(
                "SELECT * FROM tmp_user t JOIN sys_dept d ON t.dept_id = d.dept_id", EXCLUDED, TEMP).isEmpty());
    }

    @Test
    void scriptUsesLastSelectOnly() {
        String script = """
                CREATE TEMPORARY TABLE tmp_x AS SELECT 1 AS n;
                SELECT u.dept_id
                FROM sys_user u
                JOIN sys_dept d ON u.dept_id = d.dept_id
                """;
        List<RelationPair> pairs = extractor.extract(script, EXCLUDED, TEMP);
        assertEquals(1, pairs.size());
        assertEquals("sys_user", pairs.get(0).targetTable());
    }
}
