package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 为每段 SQL 补齐行首中文注释（展示用；执行时 JDBC 可忽略注释）。
 */
final class SqlCommentAnnotator {

    private static final Pattern LEADING_LINE_COMMENT = Pattern.compile("(?s)^\\s*(--[^\\n]*\\n\\s*)+.*");
    private static final Pattern TEMP_TABLE_NAME =
            Pattern.compile("(?i)(?:CREATE|DROP)\\s+TEMPORARY\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?[`\"\\[]?(\\w+)");

    private SqlCommentAnnotator() {
    }

    static String annotateSelect(String sql) {
        if (!StringUtils.hasText(sql)) {
            return sql;
        }
        if (hasLeadingComment(sql)) {
            return sql.trim();
        }
        return "-- 查询结果\n" + sql.trim();
    }

    /**
     * @param originalRaw LLM 原始脚本（可含注释），用于尽量保留模型写的说明
     */
    static List<String> annotateStatements(List<String> statements, String originalRaw) {
        if (statements == null || statements.isEmpty()) {
            return statements == null ? List.of() : statements;
        }
        List<String> rawParts = splitKeepingComments(originalRaw);
        List<String> out = new ArrayList<>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            String stmt = statements.get(i) == null ? "" : statements.get(i).trim();
            if (!StringUtils.hasText(stmt)) {
                continue;
            }
            if (hasLeadingComment(stmt)) {
                out.add(stmt);
                continue;
            }
            String fromRaw = i < rawParts.size() ? extractLeadingComment(rawParts.get(i)) : null;
            if (StringUtils.hasText(fromRaw)) {
                out.add(fromRaw.trim() + "\n" + stripLeadingComments(stmt).trim());
            } else {
                boolean last = i == statements.size() - 1;
                out.add(autoComment(stmt, i, last) + "\n" + stmt);
            }
        }
        return out;
    }

    static boolean hasLeadingComment(String sql) {
        if (!StringUtils.hasText(sql)) {
            return false;
        }
        return LEADING_LINE_COMMENT.matcher(sql.trim()).matches();
    }

    static String extractLeadingComment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return null;
        }
        String[] lines = segment.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() && sb.length() == 0) {
                continue;
            }
            if (t.startsWith("--")) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            } else if (t.startsWith("/*")) {
                // 块注释整段保留到结束
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line.trim());
                break;
            } else {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String stripLeadingComments(String sql) {
        if (!StringUtils.hasText(sql)) {
            return sql;
        }
        String[] lines = sql.split("\\R", -1);
        int i = 0;
        while (i < lines.length) {
            String t = lines[i].trim();
            if (t.isEmpty() || t.startsWith("--")) {
                i++;
                continue;
            }
            break;
        }
        if (i == 0) {
            return sql.trim();
        }
        StringBuilder sb = new StringBuilder();
        for (; i < lines.length; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString().trim();
    }

    static String autoComment(String stmt, int index, boolean last) {
        String upper = stmt.trim().toUpperCase(Locale.ROOT);
        String name = extractTempName(stmt);
        if (upper.startsWith("CREATE TEMPORARY") || upper.startsWith("CREATE TEMP ")) {
            return name != null
                    ? "-- 步骤" + (index + 1) + "：创建临时表 " + name + "，存放中间结果"
                    : "-- 步骤" + (index + 1) + "：创建临时表，存放中间结果";
        }
        if (upper.startsWith("DROP TEMPORARY") || upper.startsWith("DROP TEMP ")) {
            return name != null
                    ? "-- 步骤" + (index + 1) + "：清理临时表 " + name
                    : "-- 步骤" + (index + 1) + "：清理临时表";
        }
        if (upper.startsWith("SET @") || upper.startsWith("SET@") || upper.startsWith("SET ")) {
            return "-- 步骤" + (index + 1) + "：设置会话变量";
        }
        if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
            return last
                    ? "-- 最终查询：返回结果"
                    : "-- 步骤" + (index + 1) + "：中间查询";
        }
        return "-- 步骤" + (index + 1);
    }

    private static String extractTempName(String stmt) {
        Matcher m = TEMP_TABLE_NAME.matcher(stmt);
        return m.find() ? m.group(1) : null;
    }

    /** 按分号切分，保留各段内行注释（不做字符串引号的严格解析，够用）。 */
    static List<String> splitKeepingComments(String script) {
        List<String> parts = new ArrayList<>();
        if (!StringUtils.hasText(script)) {
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        String s = script;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char next = i + 1 < s.length() ? s.charAt(i + 1) : '\0';
            if (!inDouble && !inBacktick && c == '\'' && !inSingle) {
                inSingle = true;
                cur.append(c);
                continue;
            }
            if (inSingle) {
                cur.append(c);
                if (c == '\'' && next == '\'') {
                    cur.append(next);
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (!inSingle && !inBacktick && c == '"' && !inDouble) {
                inDouble = true;
                cur.append(c);
                continue;
            }
            if (inDouble) {
                cur.append(c);
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (!inSingle && !inDouble && c == '`' && !inBacktick) {
                inBacktick = true;
                cur.append(c);
                continue;
            }
            if (inBacktick) {
                cur.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == ';') {
                String part = cur.toString().trim();
                if (StringUtils.hasText(part)) {
                    parts.add(part);
                }
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String tail = cur.toString().trim();
        if (StringUtils.hasText(tail)) {
            parts.add(tail);
        }
        return parts;
    }
}
