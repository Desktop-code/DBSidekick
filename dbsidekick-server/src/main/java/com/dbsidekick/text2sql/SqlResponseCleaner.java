package com.dbsidekick.text2sql;

import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 从模型原文里抽出第一条 SQL，去掉前面的中文说明和后面的附注。
 * 只用于单条语句；脚本模式仍走原有按语句拆分的清洗。
 */
public final class SqlResponseCleaner {

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "CREATE",
            "DROP", "ALTER", "SET", "EXPLAIN", "SHOW", "USE");

    private SqlResponseCleaner() {
    }

    public static String clean(String rawLlmResponse) {
        if (!StringUtils.hasText(rawLlmResponse)) {
            throw new IllegalArgumentException("LLM 返回内容无有效 SQL");
        }
        String text = stripMarkdownFence(rawLlmResponse).trim();
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("LLM 返回内容无有效 SQL");
        }
        int start = indexOfFirstKeyword(text);
        if (start < 0) {
            throw new IllegalArgumentException("LLM 返回内容无有效 SQL");
        }
        String sql = start > 0 ? text.substring(start) : text;
        sql = trimTrailingNote(sql).trim();
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("LLM 返回内容无有效 SQL");
        }
        return sql;
    }

    static String stripMarkdownFence(String raw) {
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNl = s.indexOf('\n');
        if (firstNl >= 0) {
            s = s.substring(firstNl + 1);
        } else {
            s = s.replaceFirst("^```\\w*", "");
        }
        int fence = s.lastIndexOf("```");
        if (fence >= 0) {
            s = s.substring(0, fence);
        }
        return s;
    }

    /** 第一条 SQL 关键字的下标；不在引号内，且不是更长标识符的一部分。 */
    static int indexOfFirstKeyword(String text) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inSingle) {
                if (c == '\\' && i + 1 < text.length()) {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                continue;
            }
            if (!isWordStart(text, i)) {
                continue;
            }
            int end = i;
            while (end < text.length() && isWordChar(text.charAt(end))) {
                end++;
            }
            String word = text.substring(i, end).toUpperCase(Locale.ROOT);
            if (KEYWORDS.contains(word)) {
                return i;
            }
            i = Math.max(i, end - 1);
        }
        return -1;
    }

    private static String trimTrailingNote(String sql) {
        int lastSemi = lastSemicolonOutsideString(sql);
        if (lastSemi >= 0) {
            String after = sql.substring(lastSemi + 1);
            if (indexOfFirstKeyword(after) < 0) {
                sql = sql.substring(0, lastSemi + 1);
            }
        }
        String[] lines = sql.split("\n", -1);
        int keep = lines.length;
        while (keep > 1) {
            String line = lines[keep - 1].trim();
            if (!StringUtils.hasText(line)) {
                keep--;
                continue;
            }
            if (indexOfFirstKeyword(line) >= 0 || line.startsWith("--") || line.startsWith("/*")) {
                break;
            }
            if (containsCjk(line)) {
                keep--;
                continue;
            }
            break;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) {
                joined.append('\n');
            }
            joined.append(lines[i]);
        }
        return stripSameLineProse(joined.toString());
    }

    /** 同一行末尾的中文附注，例如 {@code LIMIT 10 注意：不要删除}。别名 {@code AS 销量} 保留。 */
    private static String stripSameLineProse(String sql) {
        int cut = -1;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\\' && i + 1 < sql.length()) {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                continue;
            }
            if (!isCjk(c)) {
                continue;
            }
            int prev = i - 1;
            while (prev >= 0 && Character.isWhitespace(sql.charAt(prev))) {
                prev--;
            }
            if (prev < 0) {
                continue;
            }
            String tail = sql.substring(i);
            if (indexOfFirstKeyword(tail) >= 0) {
                continue;
            }
            if (tail.codePoints().anyMatch(cp -> "：。！？，、；".indexOf(cp) >= 0 || cp == ':' )) {
                cut = i;
                while (cut > 0 && Character.isWhitespace(sql.charAt(cut - 1))) {
                    cut--;
                }
                break;
            }
        }
        return cut >= 0 ? sql.substring(0, cut) : sql;
    }

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(SqlResponseCleaner::isCjk);
    }

    private static boolean isCjk(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    private static int lastSemicolonOutsideString(String sql) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        int last = -1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle) {
                if (c == '\\' && i + 1 < sql.length()) {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '`') {
                inBacktick = true;
            } else if (c == ';') {
                last = i;
            }
        }
        return last;
    }

    private static boolean isWordStart(String text, int i) {
        if (i > 0 && isWordChar(text.charAt(i - 1))) {
            return false;
        }
        return isWordChar(text.charAt(i));
    }

    private static boolean isWordChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }
}
