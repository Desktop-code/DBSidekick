package com.dbsidekick.text2sql;

/**
 * 单轮门禁与执行的结果。需要重试时不携带最终 {@link Text2SqlResult}，由流水线再调一次模型。
 * 请求级对象，不是 Spring Bean。
 */
public final class ExecutionPass {

    public enum Kind {
        SUCCESS,
        FAIL_FINAL,
        NEED_RETRY
    }

    private final Kind kind;
    private final Text2SqlResult result;
    private final String sqlForRetry;
    private final String error;
    private final boolean scriptMode;

    private ExecutionPass(Kind kind, Text2SqlResult result, String sqlForRetry, String error, boolean scriptMode) {
        this.kind = kind;
        this.result = result;
        this.sqlForRetry = sqlForRetry;
        this.error = error;
        this.scriptMode = scriptMode;
    }

    public static ExecutionPass success(Text2SqlResult result) {
        return new ExecutionPass(Kind.SUCCESS, result, null, null, false);
    }

    public static ExecutionPass failed(Text2SqlResult result) {
        return new ExecutionPass(Kind.FAIL_FINAL, result, null, null, false);
    }

    /** @param scriptMode 本轮实际走的模式，重试生成 SQL 时沿用 */
    public static ExecutionPass retry(String sqlForRetry, String error, boolean scriptMode) {
        return new ExecutionPass(Kind.NEED_RETRY, null, sqlForRetry, error, scriptMode);
    }

    public boolean needsRetry() {
        return kind == Kind.NEED_RETRY;
    }

    public Text2SqlResult result() {
        return result;
    }

    public String sqlForRetry() {
        return sqlForRetry;
    }

    public String error() {
        return error;
    }

    public boolean scriptMode() {
        return scriptMode;
    }
}
