package com.dbsidekick.sqlguard;

import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 多语句脚本执行器：同一 Connection 共享临时表。
 * 不设 readOnly（MySQL 只读会禁 CREATE TEMPORARY TABLE），靠门禁保证无写业务表。
 * 连接池复用时临时表可能残留，CREATE 前自动 DROP IF EXISTS。
 */
@Component
public class ScriptExecutor {

    private static final Pattern CREATE_TEMP = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TEMP(?:ORARY)?\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"\\[]?[\\w.]+[`\"\\]]?)");

    private final DynamicDataSourceManager dataSourceManager;
    private final SqlGuard sqlGuard;
    private final SqlRuntimeConfig sqlRuntimeConfig;

    public ScriptExecutor(DynamicDataSourceManager dataSourceManager,
                          SqlGuard sqlGuard,
                          SqlRuntimeConfig sqlRuntimeConfig) {
        this.dataSourceManager = dataSourceManager;
        this.sqlGuard = sqlGuard;
        this.sqlRuntimeConfig = sqlRuntimeConfig;
    }
    public ScriptExecuteResult execute(String datasourceId, String script) {
        return execute(datasourceId, script, true);
    }

    /**
     * @param requireFinalSelect 见 {@link ScriptSqlGuard#validate(String, boolean)}
     */
    public ScriptExecuteResult execute(String datasourceId, String script, boolean requireFinalSelect) {
        long start = System.currentTimeMillis();
        if (!StringUtils.hasText(datasourceId)) {
            return ScriptExecuteResult.fail(script, "datasourceId 不能为空", 0);
        }
        ScriptGuardResult guard = sqlGuard.validateScript(script, requireFinalSelect);
        if (!guard.isAllowed()) {
            return ScriptExecuteResult.fail(script, guard.getReason(), System.currentTimeMillis() - start);
        }
        return executeValidated(datasourceId, guard);
    }

    public ScriptExecuteResult executeValidated(String datasourceId, ScriptGuardResult guard) {
        long start = System.currentTimeMillis();
        String script = guard.getNormalizedScript();
        List<String> statements = guard.getStatements();
        List<ScriptStepResult> steps = new ArrayList<>();
        ScriptStepResult finalSelect = null;

        try (Connection conn = dataSourceManager.openConnection(datasourceId, com.dbsidekick.datasource.ActiveDatabase.get())) {
            // 不设 readOnly：临时表需要写权限；门禁已禁止业务写操作
            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i);
                boolean isLast = i == statements.size() - 1;
                long t0 = System.currentTimeMillis();
                ScriptStepResult step = new ScriptStepResult();
                step.setIndex(i);
                step.setSql(sql);
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(sqlRuntimeConfig.queryTimeoutSeconds());
                    String bare = SqlCommentAnnotator.stripLeadingComments(sql).trim();
                    String upper = bare.toUpperCase(Locale.ROOT);
                    boolean isSelect = upper.startsWith("SELECT") || upper.startsWith("WITH");
                    if (isSelect) {
                        st.setMaxRows(sqlRuntimeConfig.defaultLimit() + 1);
                        try (ResultSet rs = st.executeQuery(sql)) {
                            fillResultSet(rs, step);
                        }
                        if (isLast) {
                            step.setFinalSelect(true);
                            finalSelect = step;
                        }
                    } else {
                        // 连接池复用 / 重复执行：CREATE 前先 DROP，避免 already exists
                        String tempName = extractCreateTempName(bare);
                        if (tempName != null) {
                            dropTempQuietly(st, tempName);
                        }
                        st.execute(sql);
                        step.setRowCount(0);
                    }
                    step.setElapsedMs(System.currentTimeMillis() - t0);
                    steps.add(step);
                } catch (Exception ex) {
                    step.setElapsedMs(System.currentTimeMillis() - t0);
                    step.setError(com.dbsidekick.config.UserFacingErrors.of(ex));
                    steps.add(step);
                    ScriptExecuteResult fail = ScriptExecuteResult.fail(
                            script, step.getError(), System.currentTimeMillis() - start);
                    fail.setSteps(steps);
                    return fail;
                }
            }
        } catch (Exception ex) {
            return ScriptExecuteResult.fail(
                    script,
                    com.dbsidekick.config.UserFacingErrors.of(ex),
                    System.currentTimeMillis() - start);
        }

        ScriptExecuteResult ok = new ScriptExecuteResult();
        ok.setSuccess(true);
        ok.setScript(script);
        ok.setSteps(steps);
        ok.setFinalResult(finalSelect);
        ok.setTotalElapsedMs(System.currentTimeMillis() - start);
        return ok;
    }

    /**
     * 多步脚本 EXPLAIN：先执行中间步骤建好临时表，再对最后一条 SELECT 做 EXPLAIN。
     * 调用方负责把结果映射为前端结构；此处返回原始 EXPLAIN 行 + 文本。
     */
    public Map<String, Object> explainLastSelect(String datasourceId, ScriptGuardResult guard,
                                                 boolean postgres) throws Exception {
        List<String> statements = guard.getStatements();
        if (statements == null || statements.isEmpty()) {
            throw new IllegalArgumentException("脚本中没有可执行语句");
        }
        String lastBare = SqlCommentAnnotator.stripLeadingComments(
                statements.get(statements.size() - 1)).trim().toUpperCase(Locale.ROOT);
        if (!(lastBare.startsWith("SELECT") || lastBare.startsWith("WITH"))) {
            throw new IllegalArgumentException("多步脚本须以 SELECT 结尾才能解释执行计划");
        }

        Set<String> createdTemps = new LinkedHashSet<>();
        String lastSql = statements.get(statements.size() - 1);
        String explainSql = postgres
                ? "EXPLAIN (FORMAT JSON) " + SqlCommentAnnotator.stripLeadingComments(lastSql).trim()
                : "EXPLAIN " + SqlCommentAnnotator.stripLeadingComments(lastSql).trim();

        try (Connection conn = dataSourceManager.openConnection(datasourceId, com.dbsidekick.datasource.ActiveDatabase.get())) {
            for (int i = 0; i < statements.size() - 1; i++) {
                String sql = statements.get(i);
                String bare = SqlCommentAnnotator.stripLeadingComments(sql).trim();
                String upper = bare.toUpperCase(Locale.ROOT);
                if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
                    // 中间 SELECT：跳过（不影响最终 EXPLAIN 所需临时表）
                    continue;
                }
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(sqlRuntimeConfig.queryTimeoutSeconds());
                    String tempName = extractCreateTempName(bare);
                    if (tempName != null) {
                        dropTempQuietly(st, tempName);
                        createdTemps.add(tempName);
                    }
                    st.execute(sql);
                }
            }
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(Math.max(15, sqlRuntimeConfig.queryTimeoutSeconds()));
                try (ResultSet rs = st.executeQuery(explainSql)) {
                    return mapExplainResultSet(rs);
                }
            } finally {
                cleanupTemps(conn, createdTemps);
            }
        }
    }

    private static Map<String, Object> mapExplainResultSet(ResultSet rs) throws Exception {
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", plan);
        out.put("planText", text.toString());
        out.put("columns", columns);
        return out;
    }

    static String extractCreateTempName(String bareSql) {
        if (!StringUtils.hasText(bareSql)) {
            return null;
        }
        Matcher m = CREATE_TEMP.matcher(bareSql);
        if (!m.find()) {
            return null;
        }
        return cleanIdent(m.group(1));
    }

    private static void dropTempQuietly(Statement st, String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return;
        }
        try {
            st.execute("DROP TEMPORARY TABLE IF EXISTS `" + tableName.replace("`", "") + "`");
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static void cleanupTemps(Connection conn, Set<String> names) {
        if (conn == null || names == null || names.isEmpty()) {
            return;
        }
        try (Statement st = conn.createStatement()) {
            for (String name : names) {
                dropTempQuietly(st, name);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static String cleanIdent(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
        int dot = n.lastIndexOf('.');
        if (dot >= 0 && dot < n.length() - 1) {
            n = n.substring(dot + 1);
        }
        return n;
    }

    private void fillResultSet(ResultSet rs, ScriptStepResult step) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(StringUtils.hasText(label) ? label : meta.getColumnName(i));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= sqlRuntimeConfig.defaultLimit()) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(columns.get(i - 1), rs.getObject(i));
            }
            rows.add(row);
        }
        step.setColumns(columns);
        step.setRows(rows);
        step.setRowCount(rows.size());
        step.setTruncated(truncated);
    }
}
