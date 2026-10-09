package com.dbsidekick.sqlguard;

import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DbType;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 真实 EXPLAIN 执行计划：单条 SELECT，或多步脚本（先建临时表再 EXPLAIN 最终 SELECT）。
 */
@Service
public class SqlExplainService {

    private final DynamicDataSourceManager dataSourceManager;
    private final SqlGuard sqlGuard;
    private final ScriptExecutor scriptExecutor;
    private final ObjectMapper objectMapper;

    public SqlExplainService(DynamicDataSourceManager dataSourceManager,
                             SqlGuard sqlGuard,
                             ScriptExecutor scriptExecutor,
                             ObjectMapper objectMapper) {
        this.dataSourceManager = dataSourceManager;
        this.sqlGuard = sqlGuard;
        this.scriptExecutor = scriptExecutor;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> explain(String datasourceId, String sql) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        if (!StringUtils.hasText(datasourceId)) {
            body.put("success", false);
            body.put("error", "datasourceId 不能为空");
            body.put("elapsedMs", 0);
            return body;
        }
        if (!StringUtils.hasText(sql)) {
            body.put("success", false);
            body.put("error", "SQL 不能为空");
            body.put("elapsedMs", 0);
            return body;
        }

        if (SqlGuard.looksLikeScript(sql)) {
            return explainScript(datasourceId.trim(), sql.trim(), start);
        }

        SqlGuardResult guard = sqlGuard.validateSelectOnly(sql);
        if (!guard.isAllowed()) {
            body.put("success", false);
            body.put("error", guard.getReason());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }

        String normalized = guard.getNormalizedSql();
        DbConfig config;
        try {
            config = dataSourceManager.getConfig(datasourceId.trim());
        } catch (IllegalArgumentException ex) {
            body.put("success", false);
            body.put("error", ex.getMessage());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }

        DbType type = config.getType() == null ? DbType.MYSQL : config.getType();
        boolean postgres = type == DbType.POSTGRESQL;
        String explainSql = postgres
                ? "EXPLAIN (FORMAT JSON) " + normalized
                : "EXPLAIN " + normalized;

        try (Connection conn = dataSourceManager.openConnection(datasourceId.trim(), com.dbsidekick.datasource.ActiveDatabase.get());
             Statement st = conn.createStatement()) {
            st.setQueryTimeout(15);
            try (ResultSet rs = st.executeQuery(explainSql)) {
                if (postgres) {
                    return mapPgExplain(rs, start);
                }
                return mapTableExplain(rs, start, false);
            }
        } catch (Exception ex) {
            body.put("success", false);
            body.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }
    }

    private Map<String, Object> explainScript(String datasourceId, String sql, long start) {
        Map<String, Object> body = new LinkedHashMap<>();
        ScriptGuardResult guard = sqlGuard.validateScript(sql, true);
        if (!guard.isAllowed()) {
            body.put("success", false);
            body.put("error", guard.getReason());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }
        DbConfig config;
        try {
            config = dataSourceManager.getConfig(datasourceId);
        } catch (IllegalArgumentException ex) {
            body.put("success", false);
            body.put("error", ex.getMessage());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }
        boolean postgres = config.getType() == DbType.POSTGRESQL;
        try {
            Map<String, Object> raw = scriptExecutor.explainLastSelect(datasourceId, guard, postgres);
            if (postgres) {
                // MySQL-style map from ScriptExecutor; PG JSON needs flatten
                Object planText = raw.get("planText");
                List<Map<String, Object>> plan = new ArrayList<>();
                if (planText != null && StringUtils.hasText(String.valueOf(planText))) {
                    flattenPgPlan(objectMapper.readTree(String.valueOf(planText)), plan, 0);
                }
                body.put("success", true);
                body.put("plan", plan);
                body.put("planText", planText == null ? "" : String.valueOf(planText));
                body.put("scriptMode", true);
                body.put("note", "已对多步脚本的最终 SELECT 解释执行计划（中间步骤已先执行）");
                body.put("elapsedMs", System.currentTimeMillis() - start);
                return body;
            }
            body.put("success", true);
            body.put("plan", raw.get("plan"));
            body.put("planText", raw.get("planText"));
            body.put("scriptMode", true);
            body.put("note", "已对多步脚本的最终 SELECT 解释执行计划（中间步骤已先执行）");
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        } catch (Exception ex) {
            body.put("success", false);
            body.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }
    }

    private Map<String, Object> mapTableExplain(ResultSet rs, long start, boolean scriptMode) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= colCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(StringUtils.hasText(label) ? label : meta.getColumnName(i));
        }
        List<Map<String, Object>> plan = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            List<String> parts = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                Object v = rs.getObject(i);
                row.put(columns.get(i - 1), v);
                parts.add(columns.get(i - 1) + "=" + (v == null ? "null" : v));
            }
            plan.add(row);
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(String.join(", ", parts));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("plan", plan);
        body.put("planText", text.toString());
        body.put("scriptMode", scriptMode);
        body.put("elapsedMs", System.currentTimeMillis() - start);
        return body;
    }

    private Map<String, Object> mapPgExplain(ResultSet rs, long start) throws Exception {
        String json = null;
        if (rs.next()) {
            Object raw = rs.getObject(1);
            json = raw == null ? null : String.valueOf(raw);
        }
        List<Map<String, Object>> plan = new ArrayList<>();
        if (StringUtils.hasText(json)) {
            flattenPgPlan(objectMapper.readTree(json), plan, 0);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("plan", plan);
        body.put("planText", json == null ? "" : json);
        body.put("elapsedMs", System.currentTimeMillis() - start);
        return body;
    }

    private void flattenPgPlan(JsonNode node, List<Map<String, Object>> out, int depth) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                flattenPgPlan(child, out, depth);
            }
            return;
        }
        JsonNode planNode = node.has("Plan") ? node.get("Plan") : node;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("depth", depth);
        row.put("nodeType", text(planNode, "Node Type"));
        row.put("relationName", text(planNode, "Relation Name"));
        row.put("alias", text(planNode, "Alias"));
        row.put("startupCost", number(planNode, "Startup Cost"));
        row.put("totalCost", number(planNode, "Total Cost"));
        row.put("planRows", number(planNode, "Plan Rows"));
        row.put("planWidth", number(planNode, "Plan Width"));
        out.add(row);
        JsonNode plans = planNode.get("Plans");
        if (plans != null && plans.isArray()) {
            for (JsonNode child : plans) {
                flattenPgPlan(child, out, depth + 1);
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Object number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.numberValue();
        }
        return v.asText();
    }
}
