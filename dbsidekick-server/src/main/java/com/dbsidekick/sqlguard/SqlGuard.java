package com.dbsidekick.sqlguard;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SQL 门禁门面：简单模式 / 脚本模式。
 */
@Component
public class SqlGuard {

    private final SimpleSqlGuard simpleSqlGuard;
    private final ScriptSqlGuard scriptSqlGuard;

    public SqlGuard(SimpleSqlGuard simpleSqlGuard, ScriptSqlGuard scriptSqlGuard) {
        this.simpleSqlGuard = simpleSqlGuard;
        this.scriptSqlGuard = scriptSqlGuard;
    }

    /** 兼容旧调用：等价于 {@link #validateSimple(String)}。 */
    public SqlGuardResult validate(String sql) {
        return validateSimple(sql);
    }

    public SqlGuardResult validateSimple(String sql) {
        return simpleSqlGuard.validate(sql, null);
    }

    public SqlGuardResult validateSimple(String sql, Set<String> allowedTables) {
        return simpleSqlGuard.validate(sql, allowedTables);
    }

    public SqlGuardResult validateSelectOnly(String sql) {
        return simpleSqlGuard.validateSelectOnly(sql);
    }

    public ScriptGuardResult validateScript(String script) {
        return scriptSqlGuard.validate(script, true, null);
    }

    public ScriptGuardResult validateScript(String script, boolean requireFinalSelect) {
        return scriptSqlGuard.validate(script, requireFinalSelect, null);
    }

    public ScriptGuardResult validateScript(String script, boolean requireFinalSelect,
                                            Set<String> allowedTables) {
        return scriptSqlGuard.validate(script, requireFinalSelect, allowedTables);
    }

    /** 是否应按脚本模式执行（多语句或临时表/会话变量语句）。 */
    public static boolean looksLikeScript(String sql) {
        if (!org.springframework.util.StringUtils.hasText(sql)) {
            return false;
        }
        String stripped = stripComments(sql).trim();
        if (!org.springframework.util.StringUtils.hasText(stripped)) {
            return false;
        }
        String[] parts = stripped.split(";");
        int n = 0;
        for (String p : parts) {
            if (org.springframework.util.StringUtils.hasText(p)) {
                n++;
            }
        }
        if (n > 1) {
            return true;
        }
        String upper = stripped.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("CREATE TEMP")
                || upper.startsWith("CREATE TEMPORARY")
                || upper.startsWith("DROP TEMP")
                || upper.startsWith("DROP TEMPORARY")
                || upper.startsWith("SET @")
                || upper.startsWith("SET@");
    }

    /**
     * 剥离双横线行注释与斜杠星号块注释（字符串内注释未做严格处理）。
     */
    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
