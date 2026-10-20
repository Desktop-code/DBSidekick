package com.dbsidekick.text2sql.legacy;

/**
 * 归档：Text2SqlService 中已迁移到 LlmSqlNormalizer 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class LlmSqlNormalizerLegacy {

    private LlmSqlNormalizerLegacy() {
    }

    // TODO: 待清理 - 已迁移至 LlmSqlNormalizer.prepareLlmSql，保留供对照，确认后由人工删除
    //     /**
    //      * 单条模式先抽出 SQL 再去掉末尾分号。脚本模式保持原清洗，避免切掉前面的临时表语句。
    //      */
    //     static String prepareLlmSql(String raw, boolean scriptMode) {
    //         if (scriptMode) {
    //             return cleanSql(raw, true);
    //         }
    //         String cleaned = SqlResponseCleaner.clean(raw);
    //         if (raw != null && Math.abs(raw.length() - cleaned.length()) >= 8) {
    //             log.info("[Sidekick] SQL cleaned: original_length={}, cleaned_length={}",
    //                     raw.length(), cleaned.length());
    //         }
    //         return cleanSql(cleaned, false);
    //     }

    // TODO: 待清理 - 已迁移至 LlmSqlNormalizer.cleanSql，保留供对照，确认后由人工删除
    //     static String cleanSql(String raw) {
    //         return cleanSql(raw, false);
    //     }

    // TODO: 待清理 - 已迁移至 LlmSqlNormalizer.cleanSql，保留供对照，确认后由人工删除
    //     static String cleanSql(String raw, boolean scriptMode) {
    //         if (raw == null) {
    //             return "";
    //         }
    //         String s = raw.trim();
    //         if (s.startsWith("```")) {
    //             int firstNl = s.indexOf('\n');
    //             if (firstNl > 0) {
    //                 s = s.substring(firstNl + 1);
    //             } else {
    //                 s = s.replaceFirst("^```\\w*", "");
    //             }
    //             int fence = s.lastIndexOf("```");
    //             if (fence >= 0) {
    //                 s = s.substring(0, fence);
    //             }
    //         }
    //         s = s.trim();
    //         if (!scriptMode && s.endsWith(";")) {
    //             s = s.substring(0, s.length() - 1).trim();
    //         }
    //         return s;
    //     }

    // TODO: 待清理 - 已迁移至 LlmSqlNormalizer.looksLikeMultiStatement，保留供对照，确认后由人工删除
    //     static boolean looksLikeMultiStatement(String sql) {
    //         if (!StringUtils.hasText(sql)) {
    //             return false;
    //         }
    //         String[] parts = sql.split(";");
    //         int n = 0;
    //         for (String p : parts) {
    //             if (StringUtils.hasText(p)) {
    //                 n++;
    //             }
    //         }
    //         return n > 1;
    //     }

    // TODO: 待清理 - 已迁移至 LlmSqlNormalizer.looksDestructive，保留供对照，确认后由人工删除
    //     static boolean looksDestructive(String question) {
    //         if (!StringUtils.hasText(question)) {
    //             return false;
    //         }
    //         String q = question.toLowerCase();
    //         return q.contains("删除") || q.contains("删掉") || q.contains("清空")
    //                 || q.contains("truncate") || q.contains("drop ")
    //                 || q.contains("drop表") || q.contains("删表")
    //                 || q.contains("update ") || q.contains("更新所有")
    //                 || q.contains("insert ") || q.contains("插入")
    //                 || q.contains("alter ") || q.contains("修改表结构");
    //     }

    // TODO: 待清理 - 已迁移至 LlmSqlNormalizer.preview，保留供对照，确认后由人工删除
    //     private static String preview(String text, int max) {
    //         if (text == null) {
    //             return "";
    //         }
    //         String t = text.trim().replace('\n', ' ');
    //         return t.length() <= max ? t : t.substring(0, max) + "...";
    //     }
}
