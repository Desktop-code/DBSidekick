package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果（单条 SELECT 或多语句脚本）。
 */
public class SqlExecuteResult {

    private boolean success;
    private String sql;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    private int rowCount;
    private boolean truncated;
    private long elapsedMs;
    private String error;
    private boolean scriptMode;
    private List<Map<String, Object>> steps = new ArrayList<>();

    public static SqlExecuteResult fail(String sql, String error, long elapsedMs) {
        SqlExecuteResult r = new SqlExecuteResult();
        r.success = false;
        r.sql = sql;
        r.error = error;
        r.elapsedMs = elapsedMs;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns == null ? new ArrayList<>() : columns;
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isScriptMode() {
        return scriptMode;
    }

    public void setScriptMode(boolean scriptMode) {
        this.scriptMode = scriptMode;
    }

    public List<Map<String, Object>> getSteps() {
        return steps;
    }

    public void setSteps(List<Map<String, Object>> steps) {
        this.steps = steps == null ? new ArrayList<>() : steps;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("sql", sql);
        map.put("columns", columns);
        map.put("rows", rows);
        map.put("rowCount", rowCount);
        map.put("truncated", truncated);
        map.put("elapsedMs", elapsedMs);
        map.put("error", error);
        map.put("scriptMode", scriptMode);
        map.put("steps", steps);
        return map;
    }
}
