package com.dbsidekick.sqlguard;

import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 简单模式门禁：仅单条 SELECT、禁多语句、可选强制 LIMIT；可选校验 allowedTables。
 */
@Component
public class SimpleSqlGuard {

    private final SqlRuntimeConfig sqlRuntimeConfig;

    public SimpleSqlGuard(SqlRuntimeConfig sqlRuntimeConfig) {
        this.sqlRuntimeConfig = sqlRuntimeConfig;
    }

    public SqlGuardResult validate(String sql) {
        return validate(sql, null);
    }

    public SqlGuardResult validate(String sql, Set<String> allowedTables) {
        SqlGuardResult base = parseSelect(sql);
        if (!base.isAllowed()) {
            return base;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(base.getNormalizedSql());
            Select select = (Select) statement;
            String normalized = SqlCommentAnnotator.annotateSelect(
                    SqlSelectNormalizer.normalize(
                            select,
                            false,
                            sqlRuntimeConfig.limitEnabled(),
                            sqlRuntimeConfig.defaultLimit(),
                            sqlRuntimeConfig.maxLimitCap()));
            String reject = AllowedTablesChecker.rejectIfDisallowed(normalized, allowedTables);
            if (reject != null) {
                return SqlGuardResult.reject(reject);
            }
            String reopen = TempTableReopenChecker.rejectIfReopen(normalized);
            if (reopen != null) {
                // 单条 SELECT：尝试自动拆副本（会变成多语句，改走脚本执行更合适；此处仍拒绝并提示）
                List<String> fixed = SqlSelectNormalizer.rewriteTempReopen(normalized);
                if (fixed != null && fixed.size() == 1) {
                    return SqlGuardResult.ok(fixed.get(0));
                }
                return SqlGuardResult.reject(reopen);
            }
            return SqlGuardResult.ok(normalized);
        } catch (Exception ex) {
            return SqlGuardResult.reject(friendlyParseError(ex));
        }
    }

    public SqlGuardResult validateSelectOnly(String sql) {
        return parseSelect(sql);
    }

    private SqlGuardResult parseSelect(String sql) {
        if (!StringUtils.hasText(sql)) {
            return SqlGuardResult.reject("SQL 不能为空");
        }

        String stripped = SqlGuard.stripComments(sql).trim();
        if (!StringUtils.hasText(stripped)) {
            return SqlGuardResult.reject("SQL 不能为空");
        }

        if (containsMultipleStatements(stripped)) {
            return SqlGuardResult.reject("禁止多语句执行");
        }

        if (stripped.endsWith(";")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }

        String upper = stripped.toUpperCase();
        if (looksLikeForbiddenDml(upper)) {
            return SqlGuardResult.reject("只允许 SELECT 查询");
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(stripped);
            if (!(statement instanceof Select)) {
                return SqlGuardResult.reject("只允许 SELECT 查询");
            }
            return SqlGuardResult.ok(statement.toString());
        } catch (Exception ex) {
            return SqlGuardResult.reject(friendlyParseError(ex));
        }
    }

    static String friendlyParseError(Exception ex) {
        String msg = ex.getMessage() == null ? "未知语法问题" : ex.getMessage();
        int at = msg.indexOf("\n");
        if (at > 0) {
            msg = msg.substring(0, at).trim();
        }
        msg = msg.replace("net.sf.jsqlparser.parser.ParseException: ", "");
        if (msg.length() > 120) {
            msg = msg.substring(0, 120) + "…";
        }
        return "SQL 语法错误：" + msg;
    }

    private boolean containsMultipleStatements(String sql) {
        String[] parts = sql.split(";");
        int nonEmpty = 0;
        for (String part : parts) {
            if (StringUtils.hasText(part)) {
                nonEmpty++;
            }
        }
        return nonEmpty > 1;
    }

    private boolean looksLikeForbiddenDml(String upper) {
        return upper.startsWith("INSERT")
                || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE")
                || upper.startsWith("DROP")
                || upper.startsWith("ALTER")
                || upper.startsWith("TRUNCATE")
                || upper.startsWith("CREATE")
                || upper.startsWith("GRANT")
                || upper.startsWith("REVOKE")
                || upper.startsWith("REPLACE")
                || upper.startsWith("MERGE");
    }
}
