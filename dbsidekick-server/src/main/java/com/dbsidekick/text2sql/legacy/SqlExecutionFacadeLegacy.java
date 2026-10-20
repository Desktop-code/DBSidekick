package com.dbsidekick.text2sql.legacy;

/**
 * 归档：Text2SqlService 中已迁移到 SqlExecutionFacade 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class SqlExecutionFacadeLegacy {

    private SqlExecutionFacadeLegacy() {
    }

    // TODO: 待清理 - 已迁移至 SqlExecutionFacade.execute，保留供对照，确认后由人工删除
    //     private Text2SqlResult validateAndExecute(String question,
    //                                                String datasourceId,
    //                                                String sessionId,
    //                                                String systemPrompt,
    //                                                String userPrompt,
    //                                                String rawSql,
    //                                                List<ReasoningStep> steps,
    //                                                StepConsumer consumer,
    //                                                long pipelineStart,
    //                                                boolean retried,
    //                                                boolean scriptMode,
    //                                                Set<String> allowedTables) {
    //         if (scriptMode) {
    //             return validateAndExecuteScript(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                     rawSql, steps, consumer, pipelineStart, retried, allowedTables);
    //         }
    //         return validateAndExecuteSimple(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                 rawSql, steps, consumer, pipelineStart, retried, allowedTables);
    //     }

    // TODO: 待清理 - 已迁移至 SqlExecutionFacade.executeSimple，保留供对照，确认后由人工删除
    //     private Text2SqlResult validateAndExecuteSimple(String question,
    //                                                       String datasourceId,
    //                                                       String sessionId,
    //                                                       String systemPrompt,
    //                                                       String userPrompt,
    //                                                       String rawSql,
    //                                                       List<ReasoningStep> steps,
    //                                                       StepConsumer consumer,
    //                                                       long pipelineStart,
    //                                                       boolean retried,
    //                                                       Set<String> allowedTables) {
    //         long t0 = System.currentTimeMillis();
    //         SqlGuardResult guard = sqlGuard.validateSimple(rawSql, allowedTables);
    //         long guardMs = System.currentTimeMillis() - t0;
    //         if (!guard.isAllowed()) {
    //             emit(steps, consumer, "安全校验", "不通过: " + guard.getReason(), guardMs);
    //             if (!retried) {
    //                 return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                         rawSql, guard.getReason(), steps, consumer, pipelineStart, false, allowedTables);
    //             }
    //             Text2SqlResult fail = Text2SqlResult.fail(question, guard.getReason(), steps,
    //                     System.currentTimeMillis() - pipelineStart);
    //             fail.setRawSql(rawSql);
    //             fail.setScriptMode(false);
    //             return fail;
    //         }
    //         String normalizedSql = guard.getNormalizedSql();
    //         emit(steps, consumer, "安全校验", "通过，normalizedSql 已就绪", guardMs);
    //
    //         t0 = System.currentTimeMillis();
    //         SqlExecuteResult exec = sqlExecutor.execute(datasourceId, normalizedSql);
    //         long execMs = System.currentTimeMillis() - t0;
    //
    //         if (exec.isSuccess()) {
    //             emit(steps, consumer, "执行",
    //                     "成功，返回 " + exec.getRowCount() + " 行" + (exec.isTruncated() ? "（已截断）" : ""),
    //                     execMs);
    //             fewShotService.trySave(datasourceId, sessionId, question, normalizedSql, exec.getRowCount());
    //             try {
    //                 relationUsageService.recordUsage(
    //                         datasourceId,
    //                         sessionId,
    //                         questionHash(question),
    //                         normalizedSql,
    //                         new UsageContext(true, exec.getRowCount(), false, false));
    //             } catch (Exception ex) {
    //                 log.warn("[Sidekick][text2sql] relation usage record skipped: {}", ex.getMessage());
    //             }
    //             Text2SqlResult ok = new Text2SqlResult();
    //             ok.setQuestion(question);
    //             ok.setSql(normalizedSql);
    //             ok.setRawSql(rawSql);
    //             ok.setColumns(exec.getColumns());
    //             ok.setRows(exec.getRows());
    //             ok.setRowCount(exec.getRowCount());
    //             ok.setTruncated(exec.isTruncated());
    //             ok.setReasoningSteps(steps);
    //             ok.setSuccess(true);
    //             ok.setScriptMode(false);
    //             ok.setElapsedMs(System.currentTimeMillis() - pipelineStart);
    //             return ok;
    //         }
    //
    //         String err = exec.getError() == null ? "执行失败" : exec.getError();
    //         emit(steps, consumer, "执行", "失败: " + err, execMs);
    //
    //         if (!retried) {
    //             return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                     normalizedSql, err, steps, consumer, pipelineStart, false, allowedTables);
    //         }
    //
    //         Text2SqlResult fail = Text2SqlResult.fail(question, err, steps,
    //                 System.currentTimeMillis() - pipelineStart);
    //         fail.setSql(normalizedSql);
    //         fail.setRawSql(rawSql);
    //         fail.setScriptMode(false);
    //         return fail;
    //     }

    // TODO: 待清理 - 已迁移至 SqlExecutionFacade.executeScript，保留供对照，确认后由人工删除
    //     private Text2SqlResult validateAndExecuteScript(String question,
    //                                                       String datasourceId,
    //                                                       String sessionId,
    //                                                       String systemPrompt,
    //                                                       String userPrompt,
    //                                                       String rawSql,
    //                                                       List<ReasoningStep> steps,
    //                                                       StepConsumer consumer,
    //                                                       long pipelineStart,
    //                                                       boolean retried,
    //                                                       Set<String> allowedTables) {
    //         long t0 = System.currentTimeMillis();
    //         ScriptGuardResult guard = sqlGuard.validateScript(rawSql, true, allowedTables);
    //         long guardMs = System.currentTimeMillis() - t0;
    //         if (!guard.isAllowed()) {
    //             String reason = guard.getReason() == null ? "安全校验未通过" : guard.getReason();
    //             emit(steps, consumer, "安全校验", "不通过: " + reason, guardMs);
    //             if (!retried) {
    //                 return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                         rawSql, reason, steps, consumer, pipelineStart, true, allowedTables);
    //             }
    //             Text2SqlResult fail = Text2SqlResult.fail(question, reason, steps,
    //                     System.currentTimeMillis() - pipelineStart);
    //             fail.setRawSql(rawSql);
    //             fail.setScriptMode(true);
    //             return fail;
    //         }
    //         emit(steps, consumer, "安全校验",
    //                 "通过，共 " + guard.getStatements().size() + " 条语句", guardMs);
    //         emit(steps, consumer, "脚本模式",
    //                 "生成 " + guard.getStatements().size() + " 条语句", 0);
    //
    //         t0 = System.currentTimeMillis();
    //         ScriptExecuteResult exec = scriptExecutor.executeValidated(datasourceId, guard);
    //         long execMs = System.currentTimeMillis() - t0;
    //
    //         if (exec.isSuccess() && exec.getFinalResult() != null) {
    //             ScriptStepResult finalStep = exec.getFinalResult();
    //             emit(steps, consumer, "执行",
    //                     "成功，返回 " + finalStep.getRowCount() + " 行"
    //                             + (finalStep.isTruncated() ? "（已截断）" : "")
    //                             + "，步骤 " + exec.getSteps().size(),
    //                     execMs);
    //             String script = exec.getScript();
    //             fewShotService.trySave(datasourceId, sessionId, question, script, finalStep.getRowCount());
    //             try {
    //                 String relationSql = lastSelectStatement(guard.getStatements());
    //                 relationUsageService.recordUsage(
    //                         datasourceId,
    //                         sessionId,
    //                         questionHash(question),
    //                         StringUtils.hasText(relationSql) ? relationSql : script,
    //                         new UsageContext(true, finalStep.getRowCount(), false, false));
    //             } catch (Exception ex) {
    //                 log.warn("[Sidekick][text2sql] relation usage record skipped: {}", ex.getMessage());
    //             }
    //             Text2SqlResult ok = new Text2SqlResult();
    //             ok.setQuestion(question);
    //             ok.setSql(script);
    //             ok.setRawSql(rawSql);
    //             ok.setColumns(finalStep.getColumns());
    //             ok.setRows(finalStep.getRows());
    //             ok.setRowCount(finalStep.getRowCount());
    //             ok.setTruncated(finalStep.isTruncated());
    //             ok.setReasoningSteps(steps);
    //             ok.setSuccess(true);
    //             ok.setScriptMode(true);
    //             ok.setScriptSteps(exec.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
    //             ok.setElapsedMs(System.currentTimeMillis() - pipelineStart);
    //             return ok;
    //         }
    //
    //         String err = exec.getError() == null ? "执行失败" : exec.getError();
    //         emit(steps, consumer, "执行", "失败: " + err, execMs);
    //
    //         if (!retried) {
    //             return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
    //                     guard.getNormalizedScript(), err, steps, consumer, pipelineStart, true, allowedTables);
    //         }
    //
    //         Text2SqlResult fail = Text2SqlResult.fail(question, err, steps,
    //                 System.currentTimeMillis() - pipelineStart);
    //         fail.setSql(guard.getNormalizedScript());
    //         fail.setRawSql(rawSql);
    //         fail.setScriptMode(true);
    //         if (exec.getSteps() != null) {
    //             fail.setScriptSteps(exec.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
    //         }
    //         return fail;
    //     }

    // TODO: 待清理 - 已迁移至 SqlExecutionFacade.lastSelectStatement，保留供对照，确认后由人工删除
    //     /** 脚本只取最后一条 SELECT/WITH，中间临时表 DDL 不计入业务关系。 */
    //     static String lastSelectStatement(List<String> statements) {
    //         if (statements == null || statements.isEmpty()) {
    //             return null;
    //         }
    //         String last = null;
    //         for (String s : statements) {
    //             if (!StringUtils.hasText(s)) {
    //                 continue;
    //             }
    //             String t = s.trim();
    //             String upper = t.toUpperCase(Locale.ROOT);
    //             if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
    //                 last = t;
    //             }
    //         }
    //         return last;
    //     }
}
