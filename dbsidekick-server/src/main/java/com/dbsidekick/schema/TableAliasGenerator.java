package com.dbsidekick.schema;

import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.config.SqliteInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 批量生成表别名 / 用途说明（写 SQLite，不改动 Milvus）。
 */
@Service
public class TableAliasGenerator {

    private static final Logger log = LoggerFactory.getLogger(TableAliasGenerator.class);

    private static final String SYSTEM_PROMPT = """
            你是数据库文档专家。根据表名、中文注释、字段列表，生成：
            1. table_aliases：3-5 个中文别名（用户可能的口语化叫法），用逗号分隔
            2. table_purpose：一句话 20-40 字，说明这张表存什么数据
            返回 JSON：{"aliases": "别名1,别名2,别名3", "purpose": "..."}
            只输出 JSON，不要 markdown。

            别名规则：
            - 通用词表：人 / 人员 / 员工 / 用户 / 账号 / 账户 / 成员 / 客户 / 顾客
            - 业务词：订单 / 交易 / 销售 / 采购 / 库存 / 商品 / 产品
            - 组织词：部门 / 组织 / 机构 / 团队
            - 至少 3 个，最多 5 个
            - 别名可以带或不带「表」字，用逗号分隔
            """.trim();

    private final SqliteInitializer sqliteInitializer;
    private final OpenAiCompatibleClient deepSeekClient;
    private final ObjectMapper objectMapper;

    public TableAliasGenerator(SqliteInitializer sqliteInitializer,
                               OpenAiCompatibleClient deepSeekClient,
                               ObjectMapper objectMapper) {
        this.sqliteInitializer = sqliteInitializer;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    public GenReport generateAll(String datasourceId, boolean overwrite) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        if (!deepSeekClient.isConfigured()) {
            throw new IllegalStateException("请先配置 DEEPSEEK_API_KEY");
        }
        long start = System.currentTimeMillis();
        String ds = datasourceId.trim();
        List<TableRow> tables = loadTables(ds);
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        int total = tables.size();
        log.info("[Sidekick][alias] start datasource={} tables={} overwrite={}", ds, total, overwrite);

        int processed = 0;
        for (TableRow row : tables) {
            processed++;
            if (!overwrite && StringUtils.hasText(row.aliases)) {
                skipped++;
                continue;
            }
            try {
                List<ColRow> cols = loadColumns(row.id, 10);
                String userPrompt = buildUserPrompt(row, cols);
                String raw = deepSeekClient.chatWithModel(SYSTEM_PROMPT, userPrompt, null);
                Parsed p = parseResponse(raw);
                if (!StringUtils.hasText(p.aliases)) {
                    failed++;
                    log.warn("[Sidekick][alias] empty aliases table={}", row.tableName);
                } else {
                    updateAliases(row.id, p.aliases, p.purpose);
                    generated++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("[Sidekick][alias] failed table={}: {}", row.tableName, ex.getMessage());
            }
            if (processed % 10 == 0 || processed == total) {
                log.info("[Sidekick][alias] progress {}/{} generated={} skipped={} failed={}",
                        processed, total, generated, skipped, failed);
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[Sidekick][alias] done datasource={} generated={} skipped={} failed={} elapsedMs={}",
                ds, generated, skipped, failed, elapsed);
        return new GenReport(generated, skipped, failed, elapsed);
    }

    private List<TableRow> loadTables(String datasourceId) {
        List<TableRow> list = new ArrayList<>();
        String sql = """
                SELECT id, table_name, table_comment, table_aliases, ddl_text
                FROM schema_table
                WHERE datasource_id = ?
                ORDER BY table_name
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TableRow row = new TableRow();
                    row.id = rs.getString("id");
                    row.tableName = rs.getString("table_name");
                    row.tableComment = rs.getString("table_comment");
                    row.aliases = rs.getString("table_aliases");
                    row.ddlText = rs.getString("ddl_text");
                    list.add(row);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("读取 schema_table 失败: " + ex.getMessage(), ex);
        }
        return list;
    }

    private List<ColRow> loadColumns(String tableId, int limit) throws Exception {
        List<ColRow> list = new ArrayList<>();
        String sql = """
                SELECT column_name, data_type, column_comment
                FROM schema_column
                WHERE table_id = ?
                ORDER BY ordinal_position
                LIMIT ?
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ColRow c = new ColRow();
                    c.name = rs.getString("column_name");
                    c.type = rs.getString("data_type");
                    c.comment = rs.getString("column_comment");
                    list.add(c);
                }
            }
        }
        return list;
    }

    private void updateAliases(String tableId, String aliases, String purpose) throws Exception {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE schema_table SET table_aliases = ?, table_purpose = ? WHERE id = ?
                     """)) {
            ps.setString(1, aliases);
            ps.setString(2, purpose);
            ps.setString(3, tableId);
            ps.executeUpdate();
        }
    }

    private static String buildUserPrompt(TableRow row, List<ColRow> cols) {
        StringBuilder sb = new StringBuilder();
        sb.append("表名：").append(nullToEmpty(row.tableName)).append('\n');
        sb.append("表注释：").append(StringUtils.hasText(row.tableComment) ? row.tableComment.trim() : "（无）").append('\n');
        sb.append("字段（前 ").append(cols.size()).append(" 个）：\n");
        for (ColRow c : cols) {
            sb.append("- ").append(nullToEmpty(c.name));
            if (StringUtils.hasText(c.type)) {
                sb.append(' ').append(c.type);
            }
            if (StringUtils.hasText(c.comment)) {
                sb.append(' ').append(c.comment.trim());
            }
            sb.append('\n');
        }
        sb.append("生成别名时考虑：用户、人员、员工、账号、账户、成员等常见叫法。");
        return sb.toString();
    }

    private Parsed parseResponse(String raw) throws Exception {
        String json = stripFence(raw);
        JsonNode root = objectMapper.readTree(json);
        Parsed p = new Parsed();
        p.aliases = normalizeAliases(root.path("aliases").asText(""));
        p.purpose = root.path("purpose").asText("").trim();
        if (p.purpose.length() > 80) {
            p.purpose = p.purpose.substring(0, 80);
        }
        return p;
    }

    private static String normalizeAliases(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String[] parts = raw.split("[,，、;；]");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String s = part.trim();
            if (!StringUtils.hasText(s)) {
                continue;
            }
            if (!out.contains(s)) {
                out.add(s);
            }
            if (out.size() >= 5) {
                break;
            }
        }
        return String.join(",", out);
    }

    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static final class GenReport {
        private final int generated;
        private final int skipped;
        private final int failed;
        private final long elapsedMs;

        public GenReport(int generated, int skipped, int failed, long elapsedMs) {
            this.generated = generated;
            this.skipped = skipped;
            this.failed = failed;
            this.elapsedMs = elapsedMs;
        }

        public int getGenerated() {
            return generated;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getFailed() {
            return failed;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("generated", generated);
            m.put("skipped", skipped);
            m.put("failed", failed);
            m.put("elapsedMs", elapsedMs);
            return m;
        }
    }

    private static final class TableRow {
        private String id;
        private String tableName;
        private String tableComment;
        private String aliases;
        private String ddlText;
    }

    private static final class ColRow {
        private String name;
        private String type;
        private String comment;
    }

    private static final class Parsed {
        private String aliases;
        private String purpose;
    }
}
