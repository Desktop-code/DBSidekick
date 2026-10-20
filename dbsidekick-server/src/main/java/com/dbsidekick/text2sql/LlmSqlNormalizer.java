package com.dbsidekick.text2sql;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 模型原文清洗、多语句判断与危险意图识别。
 */
@Component
public class LlmSqlNormalizer {

    private static final Logger log = LoggerFactory.getLogger(LlmSqlNormalizer.class);

    /**
     * 单条模式先抽出 SQL 再去掉末尾分号。脚本模式保持原清洗，避免切掉前面的临时表语句。
     */
    public static String prepareLlmSql(String raw, boolean scriptMode) {
        if (scriptMode) {
            return cleanSql(raw, true);
        }
        String cleaned = SqlResponseCleaner.clean(raw);
        if (raw != null && Math.abs(raw.length() - cleaned.length()) >= 8) {
            log.info("[Sidekick] SQL cleaned: original_length={}, cleaned_length={}",
                    raw.length(), cleaned.length());
        }
        return cleanSql(cleaned, false);
    }

    public static String cleanSql(String raw) {
        return cleanSql(raw, false);
    }

    public static String cleanSql(String raw, boolean scriptMode) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            } else {
                s = s.replaceFirst("^```\\w*", "");
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        s = s.trim();
        if (!scriptMode && s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    public static boolean looksLikeMultiStatement(String sql) {
        if (!StringUtils.hasText(sql)) {
            return false;
        }
        String[] parts = sql.split(";");
        int n = 0;
        for (String p : parts) {
            if (StringUtils.hasText(p)) {
                n++;
            }
        }
        return n > 1;
    }

    public static boolean looksDestructive(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        String q = question.toLowerCase();
        return q.contains("删除") || q.contains("删掉") || q.contains("清空")
                || q.contains("truncate") || q.contains("drop ")
                || q.contains("drop表") || q.contains("删表")
                || q.contains("update ") || q.contains("更新所有")
                || q.contains("insert ") || q.contains("插入")
                || q.contains("alter ") || q.contains("修改表结构");
    }

    public static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
