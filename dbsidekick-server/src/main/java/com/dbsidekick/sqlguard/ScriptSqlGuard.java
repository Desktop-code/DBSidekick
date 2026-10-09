package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.SetStatement;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 脚本模式门禁：允许多步临时表 + 最后一条 SELECT。
 */
@Component
public class ScriptSqlGuard {

    /** 复杂分析常需多张临时表 + 复制表规避 MySQL reopen。 */
    private static final Pattern TEMP_NAME = Pattern.compile("^(tmp_|temp_|_tmp_|_temp_).+", Pattern.CASE_INSENSITIVE);

    private final SqlRuntimeConfig sqlRuntimeConfig;

    public ScriptSqlGuard(SqlRuntimeConfig sqlRuntimeConfig) {
        this.sqlRuntimeConfig = sqlRuntimeConfig;
    }

    public ScriptGuardResult validate(String script) {
        return validate(script, true, null);
    }

    /**
     * @param requireFinalSelect true：最后一条必须是 SELECT（Text2SQL）；
     *                           false：允许仅临时表 DDL/SET（查询编辑器逐步调试）
     */
    public ScriptGuardResult validate(String script, boolean requireFinalSelect) {
        return validate(script, requireFinalSelect, null);
    }

    public ScriptGuardResult validate(String script, boolean requireFinalSelect, Set<String> allowedTables) {
        if (!StringUtils.hasText(script)) {
            return ScriptGuardResult.reject("脚本不能为空");
        }
        String original = script.trim();
        String stripped = SqlGuard.stripComments(original).trim();
        if (!StringUtils.hasText(stripped)) {
            return ScriptGuardResult.reject("脚本不能为空");
        }

        List<Statement> parsed;
        try {
            Statements stmts = CCJSqlParserUtil.parseStatements(stripped);
            parsed = stmts == null || stmts.getStatements() == null
                    ? List.of()
                    : new ArrayList<>(stmts.getStatements());
        } catch (Exception ex) {
            return ScriptGuardResult.reject(SimpleSqlGuard.friendlyParseError(ex));
        }

        List<String> normalized = new ArrayList<>();
        for (Statement st : parsed) {
            if (st == null) {
                continue;
            }
            String text = st.toString().trim();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String checked = classifyAndNormalize(st, text);
            if (checked == null) {
                return ScriptGuardResult.reject("不允许的语句类型: " + preview(text));
            }
            if (checked.startsWith("REJECT:")) {
                return ScriptGuardResult.reject(checked.substring("REJECT:".length()).trim());
            }
            normalized.add(checked);
        }

        if (normalized.isEmpty()) {
            return ScriptGuardResult.reject("脚本中没有可执行语句");
        }
        if (normalized.size() > sqlRuntimeConfig.maxStatements()) {
            return ScriptGuardResult.reject("语句数量超过限制（最多 " + sqlRuntimeConfig.maxStatements() + " 条）");
        }

        if (requireFinalSelect) {
            String last = SqlCommentAnnotator.stripLeadingComments(
                    normalized.get(normalized.size() - 1)).trim().toUpperCase(Locale.ROOT);
            if (!(last.startsWith("SELECT") || last.startsWith("WITH"))) {
                return ScriptGuardResult.reject("最后一条语句必须是 SELECT");
            }
        }

        // 用原始脚本保留 LLM 注释；缺失则自动补中文说明
        List<String> annotated = SqlCommentAnnotator.annotateStatements(normalized, original);
        // 派生表别名字符串兜底 + 临时表重复引用自动拆副本
        List<String> expanded = new ArrayList<>();
        for (String stmt : annotated) {
            String fixed = SqlSelectNormalizer.fixSqlText(stmt);
            List<String> reopenFix = SqlSelectNormalizer.rewriteTempReopen(
                    SqlCommentAnnotator.stripLeadingComments(fixed));
            if (reopenFix != null && !reopenFix.isEmpty()) {
                // 保留原注释挂在最后一条（改写后的业务 SQL）上
                String leading = SqlCommentAnnotator.extractLeadingComment(fixed);
                for (int i = 0; i < reopenFix.size(); i++) {
                    String part = reopenFix.get(i);
                    boolean last = i == reopenFix.size() - 1;
                    if (last && StringUtils.hasText(leading)) {
                        expanded.add(leading.trim() + "\n" + part);
                    } else if (!last) {
                        expanded.add("-- 自动复制临时表，规避 MySQL reopen\n" + part);
                    } else {
                        expanded.add(part);
                    }
                }
            } else {
                expanded.add(fixed);
            }
        }
        if (expanded.size() > sqlRuntimeConfig.maxStatements()) {
            return ScriptGuardResult.reject("语句数量超过限制（最多 " + sqlRuntimeConfig.maxStatements()
                    + " 条；含自动复制临时表）");
        }
        String joined = String.join(";\n", expanded);
        String reject = AllowedTablesChecker.rejectIfDisallowed(joined, allowedTables);
        if (reject != null) {
            return ScriptGuardResult.reject(reject);
        }
        // 再检测一遍（自动改写后应已通过）
        for (String stmt : expanded) {
            String reopen = TempTableReopenChecker.rejectIfReopen(stmt);
            if (reopen != null) {
                return ScriptGuardResult.reject(reopen);
            }
        }
        return ScriptGuardResult.ok(expanded);
    }

    /**
     * @return 规范化 SQL；以 REJECT: 开头表示拒绝原因；null 表示类型不允许
     */
    private String classifyAndNormalize(Statement st, String text) {
        String upper = text.trim().toUpperCase(Locale.ROOT);

        if (st instanceof Select select) {
            return normalizeSelect(select, false);
        }
        if (st instanceof CreateTable create) {
            return validateCreateTemp(create, text);
        }
        if (st instanceof Drop drop) {
            return validateDropTemp(drop, text);
        }
        if (st instanceof SetStatement) {
            return text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text;
        }
        // MySQL SET @var = ... 有时解析失败，兜底字符串判断
        if (upper.startsWith("SET @") || upper.startsWith("SET@")) {
            return text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text;
        }
        // CREATE TEMPORARY 在部分方言解析为普通 CreateTable，上面已覆盖；
        // 若解析成未知类型但文本像临时表，再兜底
        if (upper.startsWith("CREATE TEMPORARY TABLE") || upper.startsWith("CREATE TEMP TABLE")) {
            return validateCreateTempByText(text);
        }
        if (upper.startsWith("DROP TEMPORARY TABLE") || upper.startsWith("DROP TEMP TABLE")) {
            return validateDropTempByText(text);
        }
        if (looksForbidden(upper)) {
            return "REJECT:禁止写操作或非临时 DDL: " + preview(text);
        }
        return null;
    }

    private String normalizeSelect(Select select, boolean skipLimit) {
        try {
            return SqlSelectNormalizer.normalize(
                    select,
                    skipLimit,
                    sqlRuntimeConfig.limitEnabled(),
                    sqlRuntimeConfig.defaultLimit(),
                    sqlRuntimeConfig.maxLimitCap());
        } catch (Exception ex) {
            return "REJECT:" + SimpleSqlGuard.friendlyParseError(ex);
        }
    }

    private String validateCreateTemp(CreateTable create, String text) {
        boolean temporary = false;
        List<String> opts = create.getCreateOptionsStrings();
        if (opts != null) {
            for (String o : opts) {
                if (o != null && o.toUpperCase(Locale.ROOT).contains("TEMP")) {
                    temporary = true;
                    break;
                }
            }
        }
        String upperText = text.toUpperCase(Locale.ROOT);
        if (!temporary) {
            temporary = upperText.contains("TEMPORARY") || upperText.contains("TEMP TABLE");
        }
        if (!temporary) {
            return "REJECT:只允许 CREATE TEMPORARY TABLE，禁止持久化建表";
        }
        String tableName = create.getTable() == null ? null : create.getTable().getName();
        tableName = cleanIdent(tableName);
        if (!StringUtils.hasText(tableName) || !TEMP_NAME.matcher(tableName).matches()) {
            return "REJECT:临时表名必须以 tmp_/temp_/_tmp_/_temp_ 开头";
        }
        // CTAS 内层 SELECT：补派生表别名，但不强制 LIMIT
        if (create.getSelect() != null) {
            try {
                SqlSelectNormalizer.ensureDerivedTableAliases(create.getSelect());
                return SqlSelectNormalizer.fixSqlText(create.toString());
            } catch (Exception ex) {
                return "REJECT:" + SimpleSqlGuard.friendlyParseError(ex);
            }
        }
        return SqlSelectNormalizer.fixSqlText(
                text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text.trim());
    }

    private String validateCreateTempByText(String text) {
        String t = text.trim();
        String upper = t.toUpperCase(Locale.ROOT);
        int idx = upper.indexOf("TABLE");
        if (idx < 0) {
            return "REJECT:无法解析 CREATE TEMPORARY TABLE";
        }
        String rest = t.substring(idx + 5).trim();
        if (rest.toUpperCase(Locale.ROOT).startsWith("IF NOT EXISTS")) {
            rest = rest.substring("IF NOT EXISTS".length()).trim();
        }
        String name = rest.split("[\\s(]", 2)[0];
        name = cleanIdent(name);
        if (!TEMP_NAME.matcher(name).matches()) {
            return "REJECT:临时表名必须以 tmp_/temp_/_tmp_/_temp_ 开头";
        }
        String body = t.endsWith(";") ? t.substring(0, t.length() - 1).trim() : t;
        return SqlSelectNormalizer.fixSqlText(body);
    }

    private String validateDropTemp(Drop drop, String text) {
        String type = drop.getType() == null ? "" : drop.getType().toUpperCase(Locale.ROOT);
        String upper = text.toUpperCase(Locale.ROOT);
        boolean temporary = upper.contains("TEMPORARY") || upper.contains("TEMP TABLE") || "TEMPORARY".equals(type);
        if (!temporary && !"TABLE".equals(type)) {
            return "REJECT:只允许 DROP TEMPORARY TABLE";
        }
        if (!temporary && "TABLE".equals(type) && !upper.contains("TEMPORARY") && !upper.contains(" TEMP ")) {
            return "REJECT:禁止 DROP 非临时表";
        }
        String name = drop.getName() == null ? null : drop.getName().getName();
        name = cleanIdent(name);
        if (StringUtils.hasText(name) && !TEMP_NAME.matcher(name).matches()) {
            return "REJECT:临时表名必须以 tmp_/temp_/_tmp_/_temp_ 开头";
        }
        return text.endsWith(";") ? text.substring(0, text.length() - 1).trim() : text.trim();
    }

    private String validateDropTempByText(String text) {
        String t = text.trim();
        String upper = t.toUpperCase(Locale.ROOT);
        int idx = upper.lastIndexOf("TABLE");
        String rest = idx >= 0 ? t.substring(idx + 5).trim() : t;
        if (rest.toUpperCase(Locale.ROOT).startsWith("IF EXISTS")) {
            rest = rest.substring("IF EXISTS".length()).trim();
        }
        String name = rest.split("[\\s;]", 2)[0];
        name = cleanIdent(name);
        if (!TEMP_NAME.matcher(name).matches()) {
            return "REJECT:临时表名必须以 tmp_/temp_/_tmp_/_temp_ 开头";
        }
        return t.endsWith(";") ? t.substring(0, t.length() - 1).trim() : t;
    }

    private static boolean looksForbidden(String upper) {
        return upper.startsWith("INSERT")
                || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE")
                || upper.startsWith("ALTER")
                || upper.startsWith("TRUNCATE")
                || upper.startsWith("GRANT")
                || upper.startsWith("REVOKE")
                || upper.startsWith("REPLACE")
                || upper.startsWith("MERGE")
                || upper.startsWith("CREATE PROCEDURE")
                || upper.startsWith("CREATE FUNCTION")
                || upper.startsWith("CREATE TRIGGER")
                || upper.startsWith("CREATE TABLE")
                || (upper.startsWith("DROP TABLE") && !upper.contains("TEMPORARY") && !upper.contains(" TEMP "));
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

    private static String preview(String text) {
        String t = text == null ? "" : text.trim().replace('\n', ' ');
        return t.length() <= 80 ? t : t.substring(0, 80) + "…";
    }
}
