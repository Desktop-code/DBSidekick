package com.dbsidekick.text2sql.legacy;

/**
 * 归档：Text2SqlService 中已迁移到 PromptContextAssembler 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class PromptContextAssemblerLegacy {

    private PromptContextAssemblerLegacy() {
    }

    // TODO: 待清理 - 已迁移至 PromptContextAssembler.loadHistoryMessages，保留供对照，确认后由人工删除
    //     private List<Map<String, String>> loadHistoryMessages(String sessionId) {
    //         List<Map<String, String>> list = new ArrayList<>();
    //         if (!StringUtils.hasText(sessionId)) {
    //             return list;
    //         }
    //         try {
    //             List<MessageDetail> recent = chatSessionService.recentMessages(sessionId.trim(), HISTORY_MESSAGE_LIMIT);
    //             if (recent == null) {
    //                 return list;
    //             }
    //             for (MessageDetail m : recent) {
    //                 if (m == null || !StringUtils.hasText(m.getRole())) {
    //                     continue;
    //                 }
    //                 Map<String, String> row = new LinkedHashMap<>();
    //                 row.put("role", m.getRole().trim().toLowerCase(Locale.ROOT));
    //                 row.put("content", m.getContent() == null ? "" : m.getContent());
    //                 list.add(row);
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][debug-prompt] load history failed: {}", ex.getMessage());
    //         }
    //         return list;
    //     }

    // TODO: 待清理 - 已迁移至 PromptContextAssembler.loadRewriteHistory，保留供对照，确认后由人工删除
    //     /** 改写用：最近 2 轮（最多 4 条）原始 MessageDetail。 */
    //     private List<MessageDetail> loadRewriteHistory(String sessionId) {
    //         if (!StringUtils.hasText(sessionId)) {
    //             return List.of();
    //         }
    //         try {
    //             List<MessageDetail> recent = chatSessionService.recentMessages(sessionId.trim(), 4);
    //             return QueryRewriteService.trimHistory(recent);
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][text2sql] load rewrite history failed: {}", ex.getMessage());
    //             return List.of();
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 PromptContextAssembler.resolveVersion，保留供对照，确认后由人工删除
    //     private DbVersionInfo resolveVersion(String datasourceId) {
    //         try {
    //             return schemaSyncService.readVersion(datasourceId);
    //         } catch (Exception ex) {
    //             log.debug("[Sidekick][text2sql] version fallback: {}", ex.getMessage());
    //             try {
    //                 return DbVersionInfo.unknown(dataSourceManager.getConfig(datasourceId).getType());
    //             } catch (Exception ignored) {
    //                 return DbVersionInfo.of("MySQL", "8.0");
    //             }
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 PromptContextAssembler.buildHistoryText，保留供对照，确认后由人工删除
    //     private String buildHistoryText(String sessionId) {
    //         if (!StringUtils.hasText(sessionId)) {
    //             return null;
    //         }
    //         try {
    //             List<MessageDetail> recent = chatSessionService.recentMessages(sessionId.trim(), HISTORY_MESSAGE_LIMIT);
    //             if (recent == null || recent.isEmpty()) {
    //                 return null;
    //             }
    //             String text = formatHistory(recent);
    //             if (text.length() > HISTORY_TOTAL_MAX) {
    //                 int from = Math.max(0, recent.size() - 4);
    //                 text = formatHistory(recent.subList(from, recent.size()));
    //             }
    //             return StringUtils.hasText(text) ? text : null;
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][text2sql] load chat history failed: {}", ex.getMessage());
    //             return null;
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 PromptContextAssembler.formatHistory，保留供对照，确认后由人工删除
    //     private static String formatHistory(List<MessageDetail> messages) {
    //         StringBuilder sb = new StringBuilder();
    //         for (MessageDetail m : messages) {
    //             if (m == null || !StringUtils.hasText(m.getRole())) {
    //                 continue;
    //             }
    //             String role = m.getRole().trim().toLowerCase();
    //             String content = m.getContent() == null ? "" : m.getContent().trim();
    //             if ("assistant".equals(role) && content.length() > HISTORY_ASSISTANT_CONTENT_MAX) {
    //                 content = content.substring(0, HISTORY_ASSISTANT_CONTENT_MAX) + "...";
    //             }
    //             if ("user".equals(role)) {
    //                 if (sb.length() > 0) {
    //                     sb.append('\n');
    //                 }
    //                 sb.append("用户：").append(content);
    //             } else if ("assistant".equals(role)) {
    //                 if (sb.length() > 0) {
    //                     sb.append('\n');
    //                 }
    //                 sb.append("AI：").append(content);
    //             }
    //         }
    //         return sb.toString();
    //     }

    // TODO: 待清理 - 已迁移至 PromptContextAssembler.resolveDialect，保留供对照，确认后由人工删除
    //     private String resolveDialect(String datasourceId, DbVersionInfo versionInfo) {
    //         if (versionInfo != null && StringUtils.hasText(versionInfo.getProduct())) {
    //             String p = versionInfo.getProduct().toLowerCase(Locale.ROOT);
    //             if (p.contains("postgres")) {
    //                 return "PostgreSQL";
    //             }
    //             if (p.contains("mysql") || p.contains("mariadb")) {
    //                 return "MySQL";
    //             }
    //         }
    //         try {
    //             DbConfig config = dataSourceManager.getConfig(datasourceId);
    //             if (config != null && config.getType() == DbType.POSTGRESQL) {
    //                 return "PostgreSQL";
    //             }
    //         } catch (Exception ex) {
    //             log.debug("[Sidekick][text2sql] resolve dialect fallback MySQL: {}", ex.getMessage());
    //         }
    //         return "MySQL";
    //     }
}
