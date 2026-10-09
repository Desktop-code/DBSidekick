package com.dbsidekick.text2sql;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlResponseCleanerTest {

    @Test
    void stripsChinesePrefixAndParsesOnce() throws Exception {
        String raw = "从订单明细表聚合商品销量。SELECT i.product_id FROM oms_order_item i LIMIT 10";
        String cleaned = Text2SqlService.prepareLlmSql(raw, false);
        assertEquals("SELECT i.product_id FROM oms_order_item i LIMIT 10", cleaned);
        CCJSqlParserUtil.parse(cleaned);
        assertFalse(cleaned.contains("从订单"));
    }

    @Test
    void stripsFenceAndTrailingNote() {
        String raw = "```sql\nSELECT SUM(quantity) AS 销量 FROM oms_order_item;\n注意：这只是说明\n```";
        String cleaned = SqlResponseCleaner.clean(raw);
        assertTrue(cleaned.startsWith("SELECT SUM(quantity) AS 销量"));
        assertFalse(cleaned.contains("注意"));
    }

    @Test
    void scriptModeKeepsStatementsBeforeSelect() {
        String raw = "CREATE TEMPORARY TABLE tmp_a AS SELECT 1 AS id;\nSELECT * FROM tmp_a";
        String prepared = Text2SqlService.prepareLlmSql(raw, true);
        assertTrue(prepared.startsWith("CREATE TEMPORARY TABLE"));
        assertTrue(prepared.contains("SELECT * FROM tmp_a"));
    }
}
