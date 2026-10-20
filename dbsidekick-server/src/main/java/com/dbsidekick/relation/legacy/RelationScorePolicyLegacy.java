package com.dbsidekick.relation.legacy;

/**
 * 归档：RelationUsageService 中已迁移到 RelationScorePolicy 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class RelationScorePolicyLegacy {

    private RelationScorePolicyLegacy() {
    }

    // TODO: 待清理 - 已迁移至 RelationScorePolicy，保留供对照，确认后由人工删除
    // 原方法：migrateLegacyInferred / migrateV2ConfidenceRules（RelationUsageService.java 第 47-87 行）
    //     /**
    //      * relation_usage 为空时，把旧的 schema_column.is_inferred 迁入 PENDING。
    //      * app_settings.relation_migrated=true 后不再执行。
    //      */
    //     @PostConstruct
    //     public void migrateLegacyInferred() {
    //         try (Connection conn = sqliteInitializer.open()) {
    //             if (settingIsTrue(conn, "relation_migrated")) {
    //                 return;
    //             }
    //             int n = 0;
    //             if (countAll(conn) == 0) {
    //                 n = copyInferredRows(conn);
    //             }
    //             writeSetting(conn, "relation_migrated", "true");
    //             if (n > 0) {
    //                 log.info("[Sidekick] migrated {} inferred relations to relation_usage", n);
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick] inferred relation migration skipped: {}", ex.getMessage());
    //         }
    //         migrateV2ConfidenceRules();
    //     }
    //
    //     /**
    //      * 置信度 v2：只靠 USED 堆分的 CONFIRMED 降回 PENDING；阈值默认提到 6。
    //      * app_settings.relation_v2_migrated=true 后只跑一次。
    //      */
    //     void migrateV2ConfidenceRules() {
    //         try (Connection conn = sqliteInitializer.open()) {
    //             if (settingIsTrue(conn, "relation_v2_migrated")) {
    //                 return;
    //             }
    //             int demoted = demoteUsedOnlyConfirmed(conn);
    //             bumpThresholdIfOldDefault(conn);
    //             writeSetting(conn, "relation_v2_migrated", "true");
    //             log.info("[Sidekick] relation v2 migration done, demoted={}", demoted);
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick] relation v2 migration skipped: {}", ex.getMessage());
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationScorePolicy，保留供对照，确认后由人工删除
    // 原方法：recordUsage / markConfirmed / markRejected（RelationUsageService.java 第 127-196 行）
    //     public void recordUsage(String datasourceId, String sessionId, String questionHash,
    //                             String sql, UsageContext ctx) {
    //         if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(sql) || ctx == null) {
    //             return;
    //         }
    //         List<RelationPair> pairs = joinExtractor.extract(sql);
    //         if (pairs.isEmpty()) {
    //             return;
    //         }
    //         int score = scoreFor(ctx);
    //         if (score <= 0) {
    //             return;
    //         }
    //         String ds = datasourceId.trim();
    //         String sid = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
    //         String qh = StringUtils.hasText(questionHash) ? questionHash.trim() : null;
    //         String now = TS.format(Instant.now());
    //         try (Connection conn = sqliteInitializer.open()) {
    //             for (RelationPair pair : pairs) {
    //                 upsertUsage(conn, ds, pair, score, now, ctx, sid, qh);
    //             }
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][relation] recordUsage failed: {}", ex.getMessage());
    //         }
    //     }
    //
    //     public void markConfirmed(String relationId) {
    //         if (!StringUtils.hasText(relationId)) {
    //             throw new IllegalArgumentException("relationId 不能为空");
    //         }
    //         try (Connection conn = sqliteInitializer.open()) {
    //             RelationUsage r = getById(conn, relationId.trim());
    //             if (r == null) {
    //                 throw new IllegalArgumentException("关系不存在: " + relationId);
    //             }
    //             r.setStatus(RelationUsage.STATUS_CONFIRMED);
    //             r.setConfidence(r.getConfidence() + relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED));
    //             r.setSources(appendSource(r.getSources(), "MANUAL"));
    //             r.setLastUsedAt(TS.format(Instant.now()));
    //             updateRow(conn, r);
    //             writeLog(conn, r.getId(), null, null,
    //                     relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED), "MANUAL");
    //             promoteIfNeeded(conn, r);
    //         } catch (IllegalArgumentException ex) {
    //             throw ex;
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("确认关系失败: " + ex.getMessage(), ex);
    //         }
    //     }
    //
    //     public void markRejected(String relationId) {
    //         if (!StringUtils.hasText(relationId)) {
    //             throw new IllegalArgumentException("relationId 不能为空");
    //         }
    //         try (Connection conn = sqliteInitializer.open()) {
    //             RelationUsage r = getById(conn, relationId.trim());
    //             if (r == null) {
    //                 throw new IllegalArgumentException("关系不存在: " + relationId);
    //             }
    //             r.setStatus(RelationUsage.STATUS_REJECTED);
    //             r.setConfidence(relationConfigService.getInt(RelationConfigService.KEY_SCORE_REJECTED));
    //             r.setSources(appendSource(r.getSources(), "MANUAL"));
    //             r.setLastUsedAt(TS.format(Instant.now()));
    //             updateRow(conn, r);
    //         } catch (IllegalArgumentException ex) {
    //             throw ex;
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("拒绝关系失败: " + ex.getMessage(), ex);
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationScorePolicy，保留供对照，确认后由人工删除
    // 原方法：resetToPending / markPending / upsertUsage（RelationUsageService.java 第 703-765 行）
    //     public void resetToPending(String relationId) {
    //         markPending(relationId);
    //     }
    //
    //     public void markPending(String relationId) {
    //         if (!StringUtils.hasText(relationId)) {
    //             throw new IllegalArgumentException("relationId 不能为空");
    //         }
    //         try (Connection conn = sqliteInitializer.open()) {
    //             RelationUsage r = getById(conn, relationId.trim());
    //             if (r == null) {
    //                 throw new IllegalArgumentException("关系不存在: " + relationId);
    //             }
    //             r.setStatus(RelationUsage.STATUS_PENDING);
    //             r.setConfidence(0);
    //             r.setSources(appendSource(r.getSources(), "RESET"));
    //             r.setLastUsedAt(TS.format(Instant.now()));
    //             updateRow(conn, r);
    //         } catch (IllegalArgumentException ex) {
    //             throw ex;
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("恢复关系失败: " + ex.getMessage(), ex);
    //         }
    //     }
    //
    //     private void upsertUsage(Connection conn, String ds, RelationPair pair, int score, String now,
    //                              UsageContext ctx, String sessionId, String questionHash) throws Exception {
    //         RelationUsage existing = findByPair(conn, ds, pair);
    //         if (existing != null && RelationUsage.STATUS_REJECTED.equals(existing.getStatus())) {
    //             return;
    //         }
    //         if (existing != null && alreadyScored(conn, existing.getId(), sessionId, questionHash)) {
    //             return;
    //         }
    //         RelationUsage row;
    //         if (existing == null) {
    //             row = new RelationUsage();
    //             row.setId(UUID.randomUUID().toString());
    //             row.setDatasourceId(ds);
    //             row.setSourceTable(pair.sourceTable());
    //             row.setSourceColumn(pair.sourceColumn());
    //             row.setTargetTable(pair.targetTable());
    //             row.setTargetColumn(pair.targetColumn());
    //             row.setHitCount(1);
    //             row.setConfidence(score);
    //             row.setStatus(RelationUsage.STATUS_PENDING);
    //             row.setSources(ctx.sourceTag());
    //             row.setFirstSeenAt(now);
    //             row.setLastUsedAt(now);
    //             row.setLastUseRows(ctx.rowCount());
    //             insertRow(conn, row);
    //         } else {
    //             row = existing;
    //             row.setHitCount(row.getHitCount() + 1);
    //             row.setConfidence(row.getConfidence() + score);
    //             row.setSources(appendSource(row.getSources(), ctx.sourceTag()));
    //             row.setLastUsedAt(now);
    //             row.setLastUseRows(ctx.rowCount());
    //             updateRow(conn, row);
    //         }
    //         writeLog(conn, row.getId(), sessionId, questionHash, score, ctx.sourceTag());
    //         promoteIfNeeded(conn, row);
    //     }

    // TODO: 待清理 - 已迁移至 RelationScorePolicy，保留供对照，确认后由人工删除
    // 原方法：promotionThreshold / hasStrongSignal / displayKind / sourceScoreDetails / promoteIfNeeded / demoteIfNeeded（RelationUsageService.java 第 805-932 行）
    //     /**
    //      * INFERRED 且没有强信号时，晋升阈值乘以 inferred-threshold-multiplier（默认 2）。
    //      * 强信号：MANUAL / SAVED / LIKED。
    //      */
    //     static int promotionThreshold(String sources, int base, int multiplier) {
    //         int threshold = base <= 0 ? 6 : base;
    //         int mul = multiplier <= 0 ? 2 : multiplier;
    //         if (hasToken(sources, "INFERRED") && !hasStrongSignal(sources)) {
    //             return threshold * mul;
    //         }
    //         return threshold;
    //     }
    //
    //     public static boolean hasStrongSignal(String sources) {
    //         return hasToken(sources, "MANUAL")
    //                 || hasToken(sources, "SAVED")
    //                 || hasToken(sources, "LIKED");
    //     }
    //
    //     /** 展示用：CONFIRMED 但无强信号 → AUTO；有强信号 → CONFIRMED。 */
    //     public static String displayKind(RelationUsage row) {
    //         if (row == null || !StringUtils.hasText(row.getStatus())) {
    //             return "PENDING";
    //         }
    //         String status = row.getStatus().trim().toUpperCase(Locale.ROOT);
    //         if (RelationUsage.STATUS_REJECTED.equals(status)) {
    //             return "REJECTED";
    //         }
    //         if (RelationUsage.STATUS_PENDING.equals(status)) {
    //             return "PENDING";
    //         }
    //         if (RelationUsage.STATUS_CONFIRMED.equals(status)) {
    //             return hasStrongSignal(row.getSources()) ? "CONFIRMED" : "AUTO";
    //         }
    //         return status;
    //     }
    //
    //     /**
    //      * 来源明细：每个 source token 与配置加分，供管理页解释「为何被确认」。
    //      */
    //     public List<Map<String, Object>> sourceScoreDetails(String sources) {
    //         List<Map<String, Object>> out = new ArrayList<>();
    //         if (!StringUtils.hasText(sources)) {
    //             return out;
    //         }
    //         for (String raw : sources.split(",")) {
    //             if (!StringUtils.hasText(raw)) {
    //                 continue;
    //             }
    //             String tag = raw.trim().toUpperCase(Locale.ROOT);
    //             Map<String, Object> item = new LinkedHashMap<>();
    //             item.put("source", tag);
    //             item.put("label", sourceLabel(tag));
    //             item.put("score", sourceUnitScore(tag));
    //             item.put("strong", "MANUAL".equals(tag) || "SAVED".equals(tag) || "LIKED".equals(tag));
    //             out.add(item);
    //         }
    //         return out;
    //     }
    //
    //     private static String sourceLabel(String tag) {
    //         return switch (tag) {
    //             case "USED" -> "执行成功/有行";
    //             case "SAVED" -> "保存脚本";
    //             case "MANUAL" -> "手动确认";
    //             case "LIKED" -> "结果有用";
    //             case "DISLIKED" -> "结果没用";
    //             case "INFERRED" -> "推断导入";
    //             case "RESET" -> "重置";
    //             default -> tag;
    //         };
    //     }
    //
    //     private int sourceUnitScore(String tag) {
    //         return switch (tag) {
    //             case "USED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_SQL_SUCCESS)
    //                     + relationConfigService.getInt(RelationConfigService.KEY_SCORE_ROW_COUNT);
    //             case "SAVED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_SAVED);
    //             case "MANUAL" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED);
    //             case "LIKED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_LIKED);
    //             case "DISLIKED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_DISLIKED);
    //             case "REJECTED" -> relationConfigService.getInt(RelationConfigService.KEY_SCORE_REJECTED);
    //             default -> 0;
    //         };
    //     }
    //
    //     private void promoteIfNeeded(Connection conn, RelationUsage r) throws Exception {
    //         if (r == null || RelationUsage.STATUS_REJECTED.equals(r.getStatus())) {
    //             return;
    //         }
    //         if (!RelationUsage.STATUS_PENDING.equals(r.getStatus())) {
    //             return;
    //         }
    //         int threshold = promotionThreshold(
    //                 r.getSources(),
    //                 relationConfigService.confidenceThreshold(),
    //                 relationConfigService.getInt(RelationConfigService.KEY_INFERRED_MULTIPLIER, 2));
    //         if (r.getConfidence() < threshold) {
    //             return;
    //         }
    //         if (relationConfigService.requireStrongSignal() && !hasStrongSignal(r.getSources())) {
    //             return;
    //         }
    //         r.setStatus(RelationUsage.STATUS_CONFIRMED);
    //         updateRow(conn, r);
    //         log.info("[Sidekick] relation promoted: {}.{} → {}.{} (confidence={}, sources={})",
    //                 r.getSourceTable(), r.getSourceColumn(),
    //                 r.getTargetTable(), r.getTargetColumn(), r.getConfidence(), r.getSources());
    //     }
    //
    //     private void demoteIfNeeded(Connection conn, RelationUsage r) throws Exception {
    //         if (r == null || !RelationUsage.STATUS_CONFIRMED.equals(r.getStatus())) {
    //             return;
    //         }
    //         int threshold = promotionThreshold(
    //                 r.getSources(),
    //                 relationConfigService.confidenceThreshold(),
    //                 relationConfigService.getInt(RelationConfigService.KEY_INFERRED_MULTIPLIER, 2));
    //         boolean below = r.getConfidence() < threshold;
    //         boolean lostSignal = relationConfigService.requireStrongSignal() && !hasStrongSignal(r.getSources());
    //         if (below || lostSignal) {
    //             r.setStatus(RelationUsage.STATUS_PENDING);
    //             updateRow(conn, r);
    //             log.info("[Sidekick] relation demoted: {}.{} → {}.{} (confidence={}, sources={})",
    //                     r.getSourceTable(), r.getSourceColumn(),
    //                     r.getTargetTable(), r.getTargetColumn(), r.getConfidence(), r.getSources());
    //         }
    //     }

    // TODO: 待清理 - 已迁移至 RelationScorePolicy，保留供对照，确认后由人工删除
    // 原方法：recordFeedback / applyFeedback / consecutiveDislikes / scoreFor（RelationUsageService.java 第 934-1055 行）
    //     /**
    //      * 结果区 👍/👎。按 SQL 提取 JOIN，对每条关系加减分。
    //      *
    //      * @return 受影响的关系数
    //      */
    //     public int recordFeedback(String datasourceId, String sessionId, String messageId,
    //                               String sql, boolean useful) {
    //         if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(sql)) {
    //             throw new IllegalArgumentException("datasourceId 与 sql 不能为空");
    //         }
    //         List<RelationPair> pairs = joinExtractor.extract(sql);
    //         if (pairs.isEmpty()) {
    //             return 0;
    //         }
    //         int delta = useful
    //                 ? relationConfigService.getInt(RelationConfigService.KEY_SCORE_LIKED)
    //                 : relationConfigService.getInt(RelationConfigService.KEY_SCORE_DISLIKED);
    //         String tag = useful ? "LIKED" : "DISLIKED";
    //         String ds = datasourceId.trim();
    //         String sid = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
    //         String mid = StringUtils.hasText(messageId) ? messageId.trim() : null;
    //         String now = TS.format(Instant.now());
    //         int touched = 0;
    //         try (Connection conn = sqliteInitializer.open()) {
    //             for (RelationPair pair : pairs) {
    //                 if (applyFeedback(conn, ds, pair, delta, tag, now, sid, mid)) {
    //                     touched++;
    //                 }
    //             }
    //         } catch (IllegalArgumentException | IllegalStateException ex) {
    //             throw ex;
    //         } catch (Exception ex) {
    //             throw new IllegalStateException("写入关系反馈失败: " + ex.getMessage(), ex);
    //         }
    //         return touched;
    //     }
    //
    //     private boolean applyFeedback(Connection conn, String ds, RelationPair pair, int delta, String tag,
    //                                   String now, String sessionId, String messageId) throws Exception {
    //         RelationUsage existing = findByPair(conn, ds, pair);
    //         if (existing != null && RelationUsage.STATUS_REJECTED.equals(existing.getStatus())) {
    //             return false;
    //         }
    //         RelationUsage row;
    //         if (existing == null) {
    //             row = new RelationUsage();
    //             row.setId(UUID.randomUUID().toString());
    //             row.setDatasourceId(ds);
    //             row.setSourceTable(pair.sourceTable());
    //             row.setSourceColumn(pair.sourceColumn());
    //             row.setTargetTable(pair.targetTable());
    //             row.setTargetColumn(pair.targetColumn());
    //             row.setHitCount(0);
    //             row.setConfidence(delta);
    //             row.setStatus(RelationUsage.STATUS_PENDING);
    //             row.setSources(tag);
    //             row.setFirstSeenAt(now);
    //             row.setLastUsedAt(now);
    //             insertRow(conn, row);
    //         } else {
    //             row = existing;
    //             row.setConfidence(row.getConfidence() + delta);
    //             row.setSources(appendSource(row.getSources(), tag));
    //             row.setLastUsedAt(now);
    //             updateRow(conn, row);
    //         }
    //         writeLog(conn, row.getId(), sessionId, messageId, delta, tag);
    //         if ("DISLIKED".equals(tag)) {
    //             int streak = consecutiveDislikes(conn, row.getId());
    //             if (streak >= relationConfigService.maxConsecutiveDislikes()) {
    //                 row.setStatus(RelationUsage.STATUS_REJECTED);
    //                 row.setConfidence(relationConfigService.getInt(RelationConfigService.KEY_SCORE_REJECTED));
    //                 updateRow(conn, row);
    //                 return true;
    //             }
    //             demoteIfNeeded(conn, row);
    //         } else {
    //             promoteIfNeeded(conn, row);
    //         }
    //         return true;
    //     }
    //
    //     private int consecutiveDislikes(Connection conn, String relationId) throws Exception {
    //         String sql = """
    //                 SELECT source FROM relation_usage_log
    //                 WHERE relation_id = ?
    //                 ORDER BY created_at DESC, rowid DESC
    //                 LIMIT 20
    //                 """;
    //         int streak = 0;
    //         try (PreparedStatement ps = conn.prepareStatement(sql)) {
    //             ps.setString(1, relationId);
    //             try (ResultSet rs = ps.executeQuery()) {
    //                 while (rs.next()) {
    //                     String source = rs.getString("source");
    //                     if ("DISLIKED".equalsIgnoreCase(source == null ? "" : source.trim())) {
    //                         streak++;
    //                     } else {
    //                         break;
    //                     }
    //                 }
    //             }
    //         }
    //         return streak;
    //     }
    //
    //     private int scoreFor(UsageContext ctx) {
    //         int s = 0;
    //         if (ctx.sqlSuccess()) {
    //             s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_SQL_SUCCESS);
    //         }
    //         if (ctx.rowCount() > 0) {
    //             s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_ROW_COUNT);
    //         }
    //         if (ctx.savedToScript()) {
    //             s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_SAVED);
    //         }
    //         if (ctx.userConfirmed()) {
    //             s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED);
    //         }
    //         return s;
    //     }

    // TODO: 待清理 - 已迁移至 RelationScorePolicy，保留供对照，确认后由人工删除
    // 原方法：hasToken / appendSource（RelationUsageService.java 第 1132-1158 行）
    //     private static boolean hasToken(String sources, String token) {
    //         if (!StringUtils.hasText(sources) || !StringUtils.hasText(token)) {
    //             return false;
    //         }
    //         String want = token.trim().toUpperCase(Locale.ROOT);
    //         for (String p : sources.split(",")) {
    //             if (want.equals(p.trim().toUpperCase(Locale.ROOT))) {
    //                 return true;
    //             }
    //         }
    //         return false;
    //     }
    //
    //     private static String appendSource(String sources, String add) {
    //         Set<String> set = new LinkedHashSet<>();
    //         if (StringUtils.hasText(sources)) {
    //             for (String p : sources.split(",")) {
    //                 if (StringUtils.hasText(p)) {
    //                     set.add(p.trim().toUpperCase(Locale.ROOT));
    //                 }
    //             }
    //         }
    //         if (StringUtils.hasText(add)) {
    //             set.add(add.trim().toUpperCase(Locale.ROOT));
    //         }
    //         return String.join(",", set);
    //     }

}
