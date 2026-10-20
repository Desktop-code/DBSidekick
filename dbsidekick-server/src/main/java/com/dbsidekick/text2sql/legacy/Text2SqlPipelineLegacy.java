package com.dbsidekick.text2sql.legacy;

/**
 * 归档：Text2SqlService 中已迁移到 Text2SqlPipeline 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class Text2SqlPipelineLegacy {

    private Text2SqlPipelineLegacy() {
    }

    // TODO: 待清理 - 已迁移至 Text2SqlPipeline.streamQuery，保留供对照，确认后由人工删除
    //     public Text2SqlResult streamQuery(String datasourceId, String question, int topK,
    //                                        String sessionId, StepConsumer consumer) {
    //         long pipelineStart = System.currentTimeMillis();
    //         List<ReasoningStep> steps = new ArrayList<>();
    //
    //         if (!StringUtils.hasText(datasourceId)) {
    //             return Text2SqlResult.fail(question, "datasourceId 不能为空", steps, 0);
    //         }
    //         if (!StringUtils.hasText(question)) {
    //             return Text2SqlResult.fail(question, "question 不能为空", steps, 0);
    //         }
    //         if (!deepSeekClient.isConfigured()) {
    //             return Text2SqlResult.fail(question, "请先配置 DEEPSEEK_API_KEY", steps,
    //                     System.currentTimeMillis() - pipelineStart);
    //         }
    //
    //         String ds = datasourceId.trim();
    //         String q = question.trim();
    //         int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
    //
    //         long t0 = System.currentTimeMillis();
    //         boolean scriptMode = complexityAnalyzer.needsScriptMode(q);
    //         emit(steps, consumer, "理解问题",
    //                 "用户问题：" + q + (scriptMode ? "（脚本模式）" : ""),
    //                 System.currentTimeMillis() - t0);
    //         if (looksDestructive(q)) {
    //             emit(steps, consumer, "安全校验", "检测到危险意图，拒绝生成非 SELECT", 0);
    //             return Text2SqlResult.fail(q, "只允许 SELECT 查询", steps,
    //                     System.currentTimeMillis() - pipelineStart);
    //         }
    //
    //         String historyText = buildHistoryText(sessionId);
    //         List<MessageDetail> rewriteHistory = loadRewriteHistory(sessionId);
    //
    //         t0 = System.currentTimeMillis();
    //         QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(ds, q, rewriteHistory);
    //         long rewriteMs = System.currentTimeMillis() - t0;
    //         String searchQuery = StringUtils.hasText(rewrite.getRewrittenQuery())
    //                 ? rewrite.getRewrittenQuery()
    //                 : q;
    //         // Few-shot：优先用非 fallback 的 rewrittenQuery
    //         String queryForFewShot = (!rewrite.isFallback() && StringUtils.hasText(rewrite.getRewrittenQuery()))
    //                 ? rewrite.getRewrittenQuery()
    //                 : q;
    //         if (rewrite.isFallback()) {
    //             emit(steps, consumer, "改写问题",
    //                     "改写失败，使用原问题" + (rewrite.isFromCache() ? "（缓存）" : "")
    //                             + (rewrite.isUsedHistory() ? "（含历史）" : ""),
    //                     rewriteMs);
    //         } else {
    //             String hitsHint = rewrite.getHitTables() == null || rewrite.getHitTables().isEmpty()
    //                     ? ""
    //                     : "（命中候选表：" + String.join(", ", rewrite.getHitTables()) + "）";
    //             emit(steps, consumer, "改写问题",
    //                     "改写为：" + searchQuery + hitsHint + (rewrite.isFromCache() ? "（缓存）" : "")
    //                             + (rewrite.isUsedHistory() ? "（含历史）" : "")
    //                             + (rewrite.isFollowUp() ? "（追问）" : ""),
    //                     rewriteMs);
    //         }
    //
    //         t0 = System.currentTimeMillis();
    //         List<SchemaHit> hits;
    //         try {
    //             hits = hybridRetrievalService.search(ds, searchQuery, k);
    //         } catch (Exception ex) {
    //             String friendly = com.dbsidekick.config.UserFacingErrors.of(ex);
    //             emit(steps, consumer, "检索Schema", "检索失败: " + friendly,
    //                     System.currentTimeMillis() - t0);
    //             return Text2SqlResult.fail(q, friendly, steps,
    //                     System.currentTimeMillis() - pipelineStart);
    //         }
    //         long searchMs = System.currentTimeMillis() - t0;
    //         if (hits == null || hits.isEmpty()) {
    //             emit(steps, consumer, "检索Schema", "未检索到相关表", searchMs);
    //             return Text2SqlResult.fail(q, "未检索到相关表", steps, System.currentTimeMillis() - pipelineStart);
    //         }
    //         String tableNames = hits.stream()
    //                 .map(SchemaHit::getTableName)
    //                 .filter(StringUtils::hasText)
    //                 .collect(Collectors.joining(", "));
    //         emit(steps, consumer, "检索Schema",
    //                 "命中 " + hits.size() + " 张表：" + tableNames, searchMs);
    //
    //         t0 = System.currentTimeMillis();
    //         List<String> candidateTables = hits.stream()
    //                 .map(SchemaHit::getTableName)
    //                 .filter(StringUtils::hasText)
    //                 .collect(Collectors.toList());
    //         Set<String> allowedTables = new java.util.LinkedHashSet<>(candidateTables);
    //         Set<String> neighborTables = relationUsageService.listConfirmedNeighborTables(ds, candidateTables);
    //         if (!neighborTables.isEmpty()) {
    //             allowedTables.addAll(neighborTables);
    //             log.info("[Sidekick] allowedTables +confirmed neighbors: {}", String.join(", ", neighborTables));
    //         }
    //         List<FewShotExample> fewShots = List.of();
    //         if (fewShotService.isEnabled()) {
    //             fewShots = fewShotService.findSimilar(ds, queryForFewShot, candidateTables, 0);
    //             if (fewShots.isEmpty()) {
    //                 emit(steps, consumer, "检索示例", "无相似示例", System.currentTimeMillis() - t0);
    //             } else {
    //                 emit(steps, consumer, "检索示例",
    //                         "找到 " + fewShots.size() + " 个相似示例（来源：历史成功查询）",
    //                         System.currentTimeMillis() - t0);
    //                 fewShotService.markUsed(fewShots);
    //             }
    //         }
    //
    //         DbVersionInfo versionInfo = resolveVersion(ds);
    //         String dialect = resolveDialect(ds, versionInfo);
    //         String systemPrompt = scriptMode
    //                 ? promptBuilder.buildScriptSystemPrompt(versionInfo)
    //                 : promptBuilder.buildSystemPrompt(dialect, versionInfo);
    //         String userPrompt = promptBuilder.buildUserPrompt(q, hits, historyText, fewShots, scriptMode);
    //
    //         t0 = System.currentTimeMillis();
    //         String rawSql;
    //         try {
    //             rawSql = prepareLlmSql(deepSeekClient.chat(systemPrompt, userPrompt), scriptMode);
    //         } catch (IllegalArgumentException ex) {
    //             emit(steps, consumer, "生成SQL", ex.getMessage(), System.currentTimeMillis() - t0);
    //             return Text2SqlResult.fail(q, ex.getMessage(), steps, System.currentTimeMillis() - pipelineStart);
    //         } catch (Exception ex) {
    //             String friendly = com.dbsidekick.config.UserFacingErrors.of(ex);
    //             emit(steps, consumer, "生成SQL", "LLM 调用失败: " + friendly,
    //                     System.currentTimeMillis() - t0);
    //             return Text2SqlResult.fail(q, friendly, steps, System.currentTimeMillis() - pipelineStart);
    //         }
    //         long genMs = System.currentTimeMillis() - t0;
    //         if (!StringUtils.hasText(rawSql)) {
    //             emit(steps, consumer, "生成SQL", "LLM 返回为空", genMs);
    //             return Text2SqlResult.fail(q, "LLM 返回为空", steps, System.currentTimeMillis() - pipelineStart);
    //         }
    //         emit(steps, consumer, "生成SQL", preview(rawSql, 100), genMs);
    //
    //         // 若脚本模式但 LLM 只返回单条 SELECT，降级为简单模式门禁
    //         boolean effectiveScript = scriptMode && looksLikeMultiStatement(rawSql);
    //         if (scriptMode) {
    //             emit(steps, consumer, "脚本模式",
    //                     effectiveScript ? "多语句脚本" : "判定为单条 SELECT，按简单模式执行",
    //                     0);
    //         }
    //
    //         return validateAndExecute(q, ds, sessionId, systemPrompt, userPrompt, rawSql, steps, consumer,
    //                 pipelineStart, false, effectiveScript, allowedTables);
    //     }

    // TODO: 待清理 - 已迁移至 Text2SqlPipeline.debugPrompt 与 PromptContextAssembler.assembleDebug，保留供对照，确认后由人工删除
    //     /**
    //      * 诊断用：复现 Prompt 组装链路，不调用生成 SQL / 不执行 / 不 markUsed。
    //      * 与 {@link #streamQuery} 前半段保持同序同入参，便于对照追问场景。
    //      */
    //     public Map<String, Object> debugPrompt(String datasourceId, String question, String sessionId, int topK) {
    //         Map<String, Object> out = new LinkedHashMap<>();
    //         String ds = datasourceId == null ? "" : datasourceId.trim();
    //         String q = question == null ? "" : question.trim();
    //         String sid = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
    //         int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
    //
    //         out.put("question", q);
    //         out.put("sessionId", sid);
    //
    //         List<Map<String, String>> historyMessages = loadHistoryMessages(sid);
    //         out.put("historyMessages", historyMessages);
    //
    //         List<MessageDetail> rewriteHistory = loadRewriteHistory(sid);
    //         QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(ds, q, rewriteHistory);
    //         String rewritten = StringUtils.hasText(rewrite.getRewrittenQuery())
    //                 ? rewrite.getRewrittenQuery()
    //                 : q;
    //         out.put("rewrittenQuery", rewritten);
    //         out.put("rewriteUsedHistory", rewrite.isUsedHistory());
    //         out.put("followUp", rewrite.isFollowUp() || complexityAnalyzer.isFollowUp(q));
    //         out.put("rewriteFallback", rewrite.isFallback());
    //
    //         List<SchemaHit> hits = List.of();
    //         try {
    //             hits = hybridRetrievalService.search(ds, rewritten, k);
    //         } catch (Exception ex) {
    //             log.warn("[Sidekick][debug-prompt] schema search failed: {}", ex.getMessage());
    //         }
    //         List<String> schemaHitNames = hits == null ? List.of() : hits.stream()
    //                 .map(SchemaHit::getTableName)
    //                 .filter(StringUtils::hasText)
    //                 .collect(Collectors.toList());
    //         out.put("schemaHits", schemaHitNames);
    //
    //         String queryForFewShot = (!rewrite.isFallback() && StringUtils.hasText(rewrite.getRewrittenQuery()))
    //                 ? rewrite.getRewrittenQuery()
    //                 : q;
    //         List<String> candidateTables = new ArrayList<>(schemaHitNames);
    //         List<FewShotExample> fewShots = List.of();
    //         if (fewShotService.isEnabled()) {
    //             fewShots = fewShotService.findSimilar(ds, queryForFewShot, candidateTables, 0);
    //         }
    //         List<Map<String, Object>> fewShotMaps = new ArrayList<>();
    //         for (FewShotExample ex : fewShots) {
    //             if (ex == null) {
    //                 continue;
    //             }
    //             Map<String, Object> m = new LinkedHashMap<>();
    //             m.put("id", ex.getId());
    //             m.put("question", ex.getQuestion());
    //             m.put("sqlText", ex.getSqlText());
    //             m.put("tablesUsed", ex.getTablesUsed());
    //             m.put("score", ex.getScore());
    //             fewShotMaps.add(m);
    //         }
    //         out.put("fewShotExamples", fewShotMaps);
    //         out.put("fewShotSearchQuery", queryForFewShot);
    //
    //         String historyText = buildHistoryText(sid);
    //         boolean scriptMode = complexityAnalyzer.needsScriptMode(q);
    //         DbVersionInfo versionInfo = resolveVersion(ds);
    //         String dialect = resolveDialect(ds, versionInfo);
    //         String systemPrompt = scriptMode
    //                 ? promptBuilder.buildScriptSystemPrompt(versionInfo)
    //                 : promptBuilder.buildSystemPrompt(dialect, versionInfo);
    //         String userPrompt = promptBuilder.buildUserPrompt(q, hits, historyText, fewShots, scriptMode);
    //         out.put("finalSystemPrompt", systemPrompt);
    //         out.put("finalUserPrompt", userPrompt);
    //
    //         Map<String, Object> notes = new LinkedHashMap<>();
    //         notes.put("rewriteSignature", "rewrite(datasourceId, question, historyMessages)");
    //         notes.put("rewriteHistorySize", rewriteHistory.size());
    //         notes.put("historyInjectedIntoUserPrompt", StringUtils.hasText(historyText));
    //         notes.put("userPromptContainsHistorySection",
    //                 userPrompt != null && userPrompt.contains("对话历史"));
    //         notes.put("fewShotBasedOn", rewrite.isFallback() ? "question (fallback)" : "rewrittenQuery");
    //         notes.put("scriptMode", scriptMode);
    //         out.put("diagnostics", notes);
    //         return out;
    //     }

    // TODO: 待清理 - 已迁移至 Text2SqlPipeline.retryOnce，保留供对照，确认后由人工删除
    //     private Text2SqlResult retryOnce(String question,
    //                                       String datasourceId,
    //                                       String sessionId,
    //                                       String systemPrompt,
    //                                       String userPrompt,
    //                                       String previousSql,
    //                                       String err,
    //                                       List<ReasoningStep> steps,
    //                                       StepConsumer consumer,
    //                                       long pipelineStart,
    //                                       boolean scriptMode,
    //                                       Set<String> allowedTables) {
    //         log.warn("[Sidekick][text2sql] 执行失败，尝试重试一次: {}", err);
    //         long retryStart = System.currentTimeMillis();
    //         try {
    //             String retryPrompt = promptBuilder.buildRetryUserPrompt(previousSql, err);
    //             String combinedUser = userPrompt + "\n\n" + retryPrompt;
    //             String retryRaw = prepareLlmSql(deepSeekClient.chat(systemPrompt, combinedUser), scriptMode);
    //             emit(steps, consumer, "生成SQL(重试)", preview(retryRaw, 100),
    //                     System.currentTimeMillis() - retryStart);
    //             if (StringUtils.hasText(retryRaw)) {
    //                 boolean effectiveScript = scriptMode && looksLikeMultiStatement(retryRaw);
    //                 return validateAndExecute(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                         retryRaw, steps, consumer, pipelineStart, true, effectiveScript, allowedTables);
    //             }
    //         } catch (Exception ex) {
    //             emit(steps, consumer, "生成SQL(重试)", "失败: " + ex.getMessage(),
    //                     System.currentTimeMillis() - retryStart);
    //         }
    //         Text2SqlResult fail = Text2SqlResult.fail(question, err, steps,
    //                 System.currentTimeMillis() - pipelineStart);
    //         fail.setSql(previousSql);
    //         fail.setScriptMode(scriptMode);
    //         return fail;
    //     }

    // TODO: 待清理 - 已迁移至 ReasoningTrace.emit，保留供对照，确认后由人工删除
    //     private static void emit(List<ReasoningStep> steps, StepConsumer consumer,
    //                              String phase, String content, long elapsedMs) {
    //         steps.add(new ReasoningStep(phase, content, elapsedMs));
    //         if (consumer != null) {
    //             consumer.onStep(phase, content, elapsedMs);
    //         }
    //     }
}
