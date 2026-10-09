package com.dbsidekick.text2sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Text2SQL 查询结果。
 */
public class Text2SqlResult {

    private String question;
    private String sql;
    private String rawSql;
    private List<String> columns = new ArrayList<>();
    private List<Map<String, Object>> rows = new ArrayList<>();
    private int rowCount;
    private boolean truncated;
    private long elapsedMs;
    private List<ReasoningStep> reasoningSteps = new ArrayList<>();
    private boolean success;
    private String error;
    private boolean scriptMode;
    private List<Map<String, Object>> scriptSteps = new ArrayList<>();

    public static Text2SqlResult fail(String question, String error, List<ReasoningStep> steps, long elapsedMs) {
        Text2SqlResult r = new Text2SqlResult();
        r.question = question;
        r.success = false;
        r.error = error;
        r.reasoningSteps = steps == null ? new ArrayList<>() : steps;
        r.elapsedMs = elapsedMs;
        return r;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public String getRawSql() {
        return rawSql;
    }

    public void setRawSql(String rawSql) {
        this.rawSql = rawSql;
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

    public List<ReasoningStep> getReasoningSteps() {
        return reasoningSteps;
    }

    public void setReasoningSteps(List<ReasoningStep> reasoningSteps) {
        this.reasoningSteps = reasoningSteps == null ? new ArrayList<>() : reasoningSteps;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
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

    public List<Map<String, Object>> getScriptSteps() {
        return scriptSteps;
    }

    public void setScriptSteps(List<Map<String, Object>> scriptSteps) {
        this.scriptSteps = scriptSteps == null ? new ArrayList<>() : scriptSteps;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("question", question);
        map.put("sql", sql);
        map.put("rawSql", rawSql);
        map.put("columns", columns);
        map.put("rows", rows);
        map.put("rowCount", rowCount);
        map.put("truncated", truncated);
        map.put("elapsedMs", elapsedMs);
        map.put("reasoningSteps", reasoningSteps);
        map.put("success", success);
        map.put("error", error);
        map.put("scriptMode", scriptMode);
        map.put("steps", scriptSteps);
        return map;
    }

    /** SSE result 事件载荷：不含 reasoningSteps（已通过 phase 推送）。 */
    public Map<String, Object> toResultPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("question", question);
        map.put("sql", sql);
        map.put("rawSql", rawSql);
        map.put("columns", columns);
        map.put("rows", rows);
        map.put("rowCount", rowCount);
        map.put("truncated", truncated);
        map.put("elapsedMs", elapsedMs);
        map.put("success", success);
        map.put("error", error);
        map.put("scriptMode", scriptMode);
        map.put("steps", scriptSteps);
        return map;
    }
}
