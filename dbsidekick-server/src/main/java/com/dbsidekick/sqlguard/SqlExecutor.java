package com.dbsidekick.sqlguard;

import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 查询执行器：单条 SELECT（只读）或多语句脚本（临时表/SET，与 Text2SQL 脚本门禁一致）。
 */
@Component
public class SqlExecutor {

    private final DynamicDataSourceManager dataSourceManager;
    private final SqlGuard sqlGuard;
    private final ScriptExecutor scriptExecutor;
    private final SqlRuntimeConfig sqlRuntimeConfig;

    public SqlExecutor(DynamicDataSourceManager dataSourceManager,
                       SqlGuard sqlGuard,
                       ScriptExecutor scriptExecutor,
                       SqlRuntimeConfig sqlRuntimeConfig) {
        this.dataSourceManager = dataSourceManager;
        this.sqlGuard = sqlGuard;
        this.scriptExecutor = scriptExecutor;
        this.sqlRuntimeConfig = sqlRuntimeConfig;
    }
    public SqlExecuteResult execute(String datasourceId, String sql) {
        long start = System.currentTimeMillis();
        if (!StringUtils.hasText(datasourceId)) {
            return SqlExecuteResult.fail(sql, "datasourceId 不能为空", 0);
        }
        if (!StringUtils.hasText(sql)) {
            return SqlExecuteResult.fail(sql, "SQL 不能为空", 0);
        }

        // 多语句 / 临时表 / SET @ → 脚本执行（查询编辑器不强制最后一条 SELECT）
        if (SqlGuard.looksLikeScript(sql)) {
            ScriptExecuteResult scriptResult = scriptExecutor.execute(datasourceId, sql, false);
            return fromScript(scriptResult, start);
        }

        SqlGuardResult guard = sqlGuard.validateSimple(sql);
        if (!guard.isAllowed()) {
            // 简单门禁拒绝时，若其实像脚本，再尝试脚本路径（兜底）
            if (mayBeScriptRejectedAsSimple(guard.getReason())) {
                ScriptExecuteResult scriptResult = scriptExecutor.execute(datasourceId, sql, false);
                return fromScript(scriptResult, start);
            }
            return SqlExecuteResult.fail(sql, guard.getReason(), System.currentTimeMillis() - start);
        }

        String normalizedSql = guard.getNormalizedSql();
        try (Connection conn = dataSourceManager.openConnection(datasourceId, com.dbsidekick.datasource.ActiveDatabase.get())) {
            conn.setReadOnly(true);
            boolean oldAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(sqlRuntimeConfig.queryTimeoutSeconds());
                st.setMaxRows(sqlRuntimeConfig.defaultLimit() + 1);
                boolean hasResult = st.execute(normalizedSql);
                if (!hasResult) {
                    conn.rollback();
                    return SqlExecuteResult.fail(normalizedSql, "语句未返回结果集", System.currentTimeMillis() - start);
                }
                try (ResultSet rs = st.getResultSet()) {
                    SqlExecuteResult result = mapResultSet(rs, normalizedSql, start);
                    conn.rollback();
                    return result;
                }
            } finally {
                try {
                    conn.setAutoCommit(oldAutoCommit);
                } catch (Exception ignored) {
                    // ignore
                }
            }
        } catch (Exception ex) {
            return SqlExecuteResult.fail(
                    normalizedSql,
                    com.dbsidekick.config.UserFacingErrors.of(ex),
                    System.currentTimeMillis() - start);
        }
    }

    private static boolean mayBeScriptRejectedAsSimple(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("禁止多语句")
                || reason.contains("只允许 SELECT")
                || reason.contains("CREATE");
    }

    private SqlExecuteResult fromScript(ScriptExecuteResult se, long startFallback) {
        SqlExecuteResult r = new SqlExecuteResult();
        r.setScriptMode(true);
        r.setSql(se.getScript());
        r.setElapsedMs(se.getTotalElapsedMs() > 0 ? se.getTotalElapsedMs() : System.currentTimeMillis() - startFallback);
        if (se.getSteps() != null) {
            r.setSteps(se.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
        }
        if (!se.isSuccess()) {
            r.setSuccess(false);
            r.setError(se.getError());
            return r;
        }
        r.setSuccess(true);
        ScriptStepResult finalStep = se.getFinalResult();
        if (finalStep != null) {
            r.setColumns(finalStep.getColumns());
            r.setRows(finalStep.getRows());
            r.setRowCount(finalStep.getRowCount());
            r.setTruncated(finalStep.isTruncated());
        } else {
            r.setColumns(List.of());
            r.setRows(List.of());
            r.setRowCount(0);
            r.setTruncated(false);
        }
        return r;
    }

    private SqlExecuteResult mapResultSet(ResultSet rs, String sql, long start) throws Exception {
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

        SqlExecuteResult result = new SqlExecuteResult();
        result.setSuccess(true);
        result.setSql(sql);
        result.setColumns(columns);
        result.setRows(rows);
        result.setRowCount(rows.size());
        result.setTruncated(truncated);
        result.setElapsedMs(System.currentTimeMillis() - start);
        result.setScriptMode(false);
        return result;
    }
}
