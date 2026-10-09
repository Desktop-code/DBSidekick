package com.dbsidekick.sqlguard;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.util.StringUtils;

/**
 * 幻觉兑底：SQL 中引用的业务表必须落在检索命中表清单内；临时表名放行。
 */
final class AllowedTablesChecker {

    private static final Pattern TEMP_NAME =
            Pattern.compile("^(tmp_|temp_|_tmp_|_temp_).+", Pattern.CASE_INSENSITIVE);

    private AllowedTablesChecker() {
    }

    /**
     * @return null 表示通过；非 null 为拒绝原因
     */
    static String rejectIfDisallowed(String sql, Set<String> allowedTables) {
        if (allowedTables == null || allowedTables.isEmpty() || !StringUtils.hasText(sql)) {
            return null;
        }
        Set<String> allowed = normalizeSet(allowedTables);
        try {
            String stripped = SqlGuard.stripComments(sql).trim();
            Set<String> used = extractTableNames(stripped);
            Set<String> bad = new LinkedHashSet<>();
            for (String t : used) {
                if (isTemp(t) || allowed.contains(t)) {
                    continue;
                }
                bad.add(t);
            }
            if (bad.isEmpty()) {
                return null;
            }
            return "SQL 引用了未检索到的表：" + String.join(", ", bad);
        } catch (Exception ex) {
            // 解析失败不在此拦截，交给既有语法门禁
            return null;
        }
    }

    static Set<String> extractTableNames(String sql) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        if (!StringUtils.hasText(sql)) {
            return names;
        }
        TablesNamesFinder finder = new TablesNamesFinder();
        try {
            Statements stmts = CCJSqlParserUtil.parseStatements(sql);
            if (stmts != null && stmts.getStatements() != null) {
                for (Statement st : stmts.getStatements()) {
                    if (st == null) {
                        continue;
                    }
                    for (String t : finder.getTableList(st)) {
                        String n = cleanIdent(t);
                        if (StringUtils.hasText(n)) {
                            names.add(n.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Statement st = CCJSqlParserUtil.parse(sql);
            for (String t : finder.getTableList(st)) {
                String n = cleanIdent(t);
                if (StringUtils.hasText(n)) {
                    names.add(n.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static Set<String> normalizeSet(Set<String> allowedTables) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : allowedTables) {
            String n = cleanIdent(t);
            if (StringUtils.hasText(n)) {
                out.add(n.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static boolean isTemp(String name) {
        return name != null && TEMP_NAME.matcher(name).matches();
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
}
