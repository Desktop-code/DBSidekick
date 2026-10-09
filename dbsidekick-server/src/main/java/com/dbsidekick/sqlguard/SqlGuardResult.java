package com.dbsidekick.sqlguard;

/**
 * SQL 门禁校验结果。
 */
public class SqlGuardResult {

    private final boolean allowed;
    private final String reason;
    private final String normalizedSql;

    private SqlGuardResult(boolean allowed, String reason, String normalizedSql) {
        this.allowed = allowed;
        this.reason = reason;
        this.normalizedSql = normalizedSql;
    }

    public static SqlGuardResult ok(String normalizedSql) {
        return new SqlGuardResult(true, null, normalizedSql);
    }

    public static SqlGuardResult reject(String reason) {
        return new SqlGuardResult(false, reason, null);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public String getNormalizedSql() {
        return normalizedSql;
    }
}
