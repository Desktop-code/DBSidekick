package com.dbsidekick.text2sql;

import com.dbsidekick.ai.hybrid.HybridRetrievalService;
import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.asset.ChatSessionService;
import com.dbsidekick.asset.MessageDetail;
import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DbType;
import com.dbsidekick.datasource.DbVersionInfo;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.dbsidekick.relation.RelationUsageService;
import com.dbsidekick.relation.UsageContext;
import com.dbsidekick.schema.SchemaSyncService;
import com.dbsidekick.sqlguard.ScriptExecuteResult;
import com.dbsidekick.sqlguard.ScriptExecutor;
import com.dbsidekick.sqlguard.ScriptGuardResult;
import com.dbsidekick.sqlguard.ScriptStepResult;
import com.dbsidekick.sqlguard.SqlExecuteResult;
import com.dbsidekick.sqlguard.SqlExecutor;
import com.dbsidekick.sqlguard.SqlGuard;
import com.dbsidekick.sqlguard.SqlGuardResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Text2SQL 流水线：检索 Schema → LLM 生成 → 门禁 → 执行（失败可重试 1 次）。
 * 支持简单 SELECT 与多语句脚本模式。
 */
@Service
public class Text2SqlService {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlService.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;
    private static final int HISTORY_MESSAGE_LIMIT = 6;
    private static final int HISTORY_ASSISTANT_CONTENT_MAX = 200;
    private static final int HISTORY_TOTAL_MAX = 1500;

    private final OpenAiCompatibleClient deepSeekClient;
    private final PromptBuilder promptBuilder;
    private final HybridRetrievalService hybridRetrievalService;
    private final SqlGuard sqlGuard;
    private final SqlExecutor sqlExecutor;
    private final ScriptExecutor scriptExecutor;
    private final DynamicDataSourceManager dataSourceManager;
    private final ChatSessionService chatSessionService;
    private final QueryRewriteService queryRewriteService;
    private final FewShotService fewShotService;
    private final RelationUsageService relationUsageService;
    private final QueryComplexityAnalyzer complexityAnalyzer;
    private final SchemaSyncService schemaSyncService;

    public Text2SqlService(OpenAiCompatibleClient deepSeekClient,
                           PromptBuilder promptBuilder,
                           HybridRetrievalService hybridRetrievalService,
                           SqlGuard sqlGuard,
                           SqlExecutor sqlExecutor,
                           ScriptExecutor scriptExecutor,
                           DynamicDataSourceManager dataSourceManager,
                           ChatSessionService chatSessionService,
                           QueryRewriteService queryRewriteService,
                           FewShotService fewShotService,
                           RelationUsageService relationUsageService,
                           QueryComplexityAnalyzer complexityAnalyzer,
                           SchemaSyncService schemaSyncService) {
        this.deepSeekClient = deepSeekClient;
        this.promptBuilder = promptBuilder;
        this.hybridRetrievalService = hybridRetrievalService;
        this.sqlGuard = sqlGuard;
        this.sqlExecutor = sqlExecutor;
        this.scriptExecutor = scriptExecutor;
        this.dataSourceManager = dataSourceManager;
        this.chatSessionService = chatSessionService;
        this.queryRewriteService = queryRewriteService;
        this.fewShotService = fewShotService;
        this.relationUsageService = relationUsageService;
        this.complexityAnalyzer = complexityAnalyzer;
        this.schemaSyncService = schemaSyncService;
    }

    public Text2SqlResult query(String datasourceId, String question, int topK) {
        return streamQuery(datasourceId, question, topK, null, null);
    }

    public Text2SqlResult streamQuery(String datasourceId, String question, int topK, StepConsumer consumer) {
        return streamQuery(datasourceId, question, topK, null, consumer);
    }

    public Text2SqlResult streamQuery(String datasourceId, String question, int topK,
                                       String sessionId, StepConsumer consumer) {
        long pipelineStart = System.currentTimeMillis();
        List<ReasoningStep> steps = new ArrayList<>();

        if (!StringUtils.hasText(datasourceId)) {
            return Text2SqlResult.fail(question, "datasourceId 不能为空", steps, 0);
        }
        if (!StringUtils.hasText(question)) {
            return Text2SqlResult.fail(question, "question 不能为空", steps, 0);
        }
        if (!deepSeekClient.isConfigured()) {
            return Text2SqlResult.fail(question, "请先配置 DEEPSEEK_API_KEY", steps,
                    System.currentTimeMillis() - pipelineStart);
        }

        String ds = datasourceId.trim();
        String q = question.trim();
        int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);

        long t0 = System.currentTimeMillis();
        boolean scriptMode = complexityAnalyzer.needsScriptMode(q);
        emit(steps, consumer, "理解问题",
                "用户问题：" + q + (scriptMode ? "（脚本模式）" : ""),
                System.currentTimeMillis() - t0);
        if (looksDestructive(q)) {
            emit(steps, consumer, "安全校验", "检测到危险意图，拒绝生成非 SELECT", 0);
            return Text2SqlResult.fail(q, "只允许 SELECT 查询", steps,
                    System.currentTimeMillis() - pipelineStart);
        }

        String historyText = buildHistoryText(sessionId);
        List<MessageDetail> rewriteHistory = loadRewriteHistory(sessionId);

        t0 = System.currentTimeMillis();
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(ds, q, rewriteHistory);
        long rewriteMs = System.currentTimeMillis() - t0;
        String searchQuery = StringUtils.hasText(rewrite.getRewrittenQuery())
                ? rewrite.getRewrittenQuery()
                : q;
        // Few-shot：优先用非 fallback 的 rewrittenQuery
        String queryForFewShot = (!rewrite.isFallback() && StringUtils.hasText(rewrite.getRewrittenQuery()))
                ? rewrite.getRewrittenQuery()
                : q;
        if (rewrite.isFallback()) {
            emit(steps, consumer, "改写问题",
                    "改写失败，使用原问题" + (rewrite.isFromCache() ? "（缓存）" : "")
                            + (rewrite.isUsedHistory() ? "（含历史）" : ""),
                    rewriteMs);
        } else {
            String hitsHint = rewrite.getHitTables() == null || rewrite.getHitTables().isEmpty()
                    ? ""
                    : "（命中候选表：" + String.join(", ", rewrite.getHitTables()) + "）";
            emit(steps, consumer, "改写问题",
                    "改写为：" + searchQuery + hitsHint + (rewrite.isFromCache() ? "（缓存）" : "")
                            + (rewrite.isUsedHistory() ? "（含历史）" : "")
                            + (rewrite.isFollowUp() ? "（追问）" : ""),
                    rewriteMs);
        }

        t0 = System.currentTimeMillis();
        List<SchemaHit> hits;
        try {
            hits = hybridRetrievalService.search(ds, searchQuery, k);
        } catch (Exception ex) {
            String friendly = com.dbsidekick.config.UserFacingErrors.of(ex);
            emit(steps, consumer, "检索Schema", "检索失败: " + friendly,
                    System.currentTimeMillis() - t0);
            return Text2SqlResult.fail(q, friendly, steps,
                    System.currentTimeMillis() - pipelineStart);
        }
        long searchMs = System.currentTimeMillis() - t0;
        if (hits == null || hits.isEmpty()) {
            emit(steps, consumer, "检索Schema", "未检索到相关表", searchMs);
            return Text2SqlResult.fail(q, "未检索到相关表", steps, System.currentTimeMillis() - pipelineStart);
        }
        String tableNames = hits.stream()
                .map(SchemaHit::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
        emit(steps, consumer, "检索Schema",
                "命中 " + hits.size() + " 张表：" + tableNames, searchMs);

        t0 = System.currentTimeMillis();
        List<String> candidateTables = hits.stream()
                .map(SchemaHit::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        Set<String> allowedTables = new java.util.LinkedHashSet<>(candidateTables);
        Set<String> neighborTables = relationUsageService.listConfirmedNeighborTables(ds, candidateTables);
        if (!neighborTables.isEmpty()) {
            allowedTables.addAll(neighborTables);
            log.info("[Sidekick] allowedTables +confirmed neighbors: {}", String.join(", ", neighborTables));
        }
        List<FewShotExample> fewShots = List.of();
        if (fewShotService.isEnabled()) {
            fewShots = fewShotService.findSimilar(ds, queryForFewShot, candidateTables, 0);
            if (fewShots.isEmpty()) {
                emit(steps, consumer, "检索示例", "无相似示例", System.currentTimeMillis() - t0);
            } else {
                emit(steps, consumer, "检索示例",
                        "找到 " + fewShots.size() + " 个相似示例（来源：历史成功查询）",
                        System.currentTimeMillis() - t0);
                fewShotService.markUsed(fewShots);
            }
        }

        DbVersionInfo versionInfo = resolveVersion(ds);
        String dialect = resolveDialect(ds, versionInfo);
        String systemPrompt = scriptMode
                ? promptBuilder.buildScriptSystemPrompt(versionInfo)
                : promptBuilder.buildSystemPrompt(dialect, versionInfo);
        String userPrompt = promptBuilder.buildUserPrompt(q, hits, historyText, fewShots, scriptMode);

        t0 = System.currentTimeMillis();
        String rawSql;
        try {
            rawSql = prepareLlmSql(deepSeekClient.chat(systemPrompt, userPrompt), scriptMode);
        } catch (IllegalArgumentException ex) {
            emit(steps, consumer, "生成SQL", ex.getMessage(), System.currentTimeMillis() - t0);
            return Text2SqlResult.fail(q, ex.getMessage(), steps, System.currentTimeMillis() - pipelineStart);
        } catch (Exception ex) {
            String friendly = com.dbsidekick.config.UserFacingErrors.of(ex);
            emit(steps, consumer, "生成SQL", "LLM 调用失败: " + friendly,
                    System.currentTimeMillis() - t0);
            return Text2SqlResult.fail(q, friendly, steps, System.currentTimeMillis() - pipelineStart);
        }
        long genMs = System.currentTimeMillis() - t0;
        if (!StringUtils.hasText(rawSql)) {
            emit(steps, consumer, "生成SQL", "LLM 返回为空", genMs);
            return Text2SqlResult.fail(q, "LLM 返回为空", steps, System.currentTimeMillis() - pipelineStart);
        }
        emit(steps, consumer, "生成SQL", preview(rawSql, 100), genMs);

        // 若脚本模式但 LLM 只返回单条 SELECT，降级为简单模式门禁
        boolean effectiveScript = scriptMode && looksLikeMultiStatement(rawSql);
        if (scriptMode) {
            emit(steps, consumer, "脚本模式",
                    effectiveScript ? "多语句脚本" : "判定为单条 SELECT，按简单模式执行",
                    0);
        }

        return validateAndExecute(q, ds, sessionId, systemPrompt, userPrompt, rawSql, steps, consumer,
                pipelineStart, false, effectiveScript, allowedTables);
    }

    /**
     * 诊断用：复现 Prompt 组装链路，不调用生成 SQL / 不执行 / 不 markUsed。
     * 与 {@link #streamQuery} 前半段保持同序同入参，便于对照追问场景。
     */
    public Map<String, Object> debugPrompt(String datasourceId, String question, String sessionId, int topK) {
        Map<String, Object> out = new LinkedHashMap<>();
        String ds = datasourceId == null ? "" : datasourceId.trim();
        String q = question == null ? "" : question.trim();
        String sid = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
        int k = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);

        out.put("question", q);
        out.put("sessionId", sid);

        List<Map<String, String>> historyMessages = loadHistoryMessages(sid);
        out.put("historyMessages", historyMessages);

        List<MessageDetail> rewriteHistory = loadRewriteHistory(sid);
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(ds, q, rewriteHistory);
        String rewritten = StringUtils.hasText(rewrite.getRewrittenQuery())
                ? rewrite.getRewrittenQuery()
                : q;
        out.put("rewrittenQuery", rewritten);
        out.put("rewriteUsedHistory", rewrite.isUsedHistory());
        out.put("followUp", rewrite.isFollowUp() || complexityAnalyzer.isFollowUp(q));
        out.put("rewriteFallback", rewrite.isFallback());

        List<SchemaHit> hits = List.of();
        try {
            hits = hybridRetrievalService.search(ds, rewritten, k);
        } catch (Exception ex) {
            log.warn("[Sidekick][debug-prompt] schema search failed: {}", ex.getMessage());
        }
        List<String> schemaHitNames = hits == null ? List.of() : hits.stream()
                .map(SchemaHit::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        out.put("schemaHits", schemaHitNames);

        String queryForFewShot = (!rewrite.isFallback() && StringUtils.hasText(rewrite.getRewrittenQuery()))
                ? rewrite.getRewrittenQuery()
                : q;
        List<String> candidateTables = new ArrayList<>(schemaHitNames);
        List<FewShotExample> fewShots = List.of();
        if (fewShotService.isEnabled()) {
            fewShots = fewShotService.findSimilar(ds, queryForFewShot, candidateTables, 0);
        }
        List<Map<String, Object>> fewShotMaps = new ArrayList<>();
        for (FewShotExample ex : fewShots) {
            if (ex == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ex.getId());
            m.put("question", ex.getQuestion());
            m.put("sqlText", ex.getSqlText());
            m.put("tablesUsed", ex.getTablesUsed());
            m.put("score", ex.getScore());
            fewShotMaps.add(m);
        }
        out.put("fewShotExamples", fewShotMaps);
        out.put("fewShotSearchQuery", queryForFewShot);

        String historyText = buildHistoryText(sid);
        boolean scriptMode = complexityAnalyzer.needsScriptMode(q);
        DbVersionInfo versionInfo = resolveVersion(ds);
        String dialect = resolveDialect(ds, versionInfo);
        String systemPrompt = scriptMode
                ? promptBuilder.buildScriptSystemPrompt(versionInfo)
                : promptBuilder.buildSystemPrompt(dialect, versionInfo);
        String userPrompt = promptBuilder.buildUserPrompt(q, hits, historyText, fewShots, scriptMode);
        out.put("finalSystemPrompt", systemPrompt);
        out.put("finalUserPrompt", userPrompt);

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("rewriteSignature", "rewrite(datasourceId, question, historyMessages)");
        notes.put("rewriteHistorySize", rewriteHistory.size());
        notes.put("historyInjectedIntoUserPrompt", StringUtils.hasText(historyText));
        notes.put("userPromptContainsHistorySection",
                userPrompt != null && userPrompt.contains("对话历史"));
        notes.put("fewShotBasedOn", rewrite.isFallback() ? "question (fallback)" : "rewrittenQuery");
        notes.put("scriptMode", scriptMode);
        out.put("diagnostics", notes);
        return out;
    }

    private List<Map<String, String>> loadHistoryMessages(String sessionId) {
        List<Map<String, String>> list = new ArrayList<>();
        if (!StringUtils.hasText(sessionId)) {
            return list;
        }
        try {
            List<MessageDetail> recent = chatSessionService.recentMessages(sessionId.trim(), HISTORY_MESSAGE_LIMIT);
            if (recent == null) {
                return list;
            }
            for (MessageDetail m : recent) {
                if (m == null || !StringUtils.hasText(m.getRole())) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", m.getRole().trim().toLowerCase(Locale.ROOT));
                row.put("content", m.getContent() == null ? "" : m.getContent());
                list.add(row);
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][debug-prompt] load history failed: {}", ex.getMessage());
        }
        return list;
    }

    /** 改写用：最近 2 轮（最多 4 条）原始 MessageDetail。 */
    private List<MessageDetail> loadRewriteHistory(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        try {
            List<MessageDetail> recent = chatSessionService.recentMessages(sessionId.trim(), 4);
            return QueryRewriteService.trimHistory(recent);
        } catch (Exception ex) {
            log.warn("[Sidekick][text2sql] load rewrite history failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private DbVersionInfo resolveVersion(String datasourceId) {
        try {
            return schemaSyncService.readVersion(datasourceId);
        } catch (Exception ex) {
            log.debug("[Sidekick][text2sql] version fallback: {}", ex.getMessage());
            try {
                return DbVersionInfo.unknown(dataSourceManager.getConfig(datasourceId).getType());
            } catch (Exception ignored) {
                return DbVersionInfo.of("MySQL", "8.0");
            }
        }
    }

    private String buildHistoryText(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        try {
            List<MessageDetail> recent = chatSessionService.recentMessages(sessionId.trim(), HISTORY_MESSAGE_LIMIT);
            if (recent == null || recent.isEmpty()) {
                return null;
            }
            String text = formatHistory(recent);
            if (text.length() > HISTORY_TOTAL_MAX) {
                int from = Math.max(0, recent.size() - 4);
                text = formatHistory(recent.subList(from, recent.size()));
            }
            return StringUtils.hasText(text) ? text : null;
        } catch (Exception ex) {
            log.warn("[Sidekick][text2sql] load chat history failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String formatHistory(List<MessageDetail> messages) {
        StringBuilder sb = new StringBuilder();
        for (MessageDetail m : messages) {
            if (m == null || !StringUtils.hasText(m.getRole())) {
                continue;
            }
            String role = m.getRole().trim().toLowerCase();
            String content = m.getContent() == null ? "" : m.getContent().trim();
            if ("assistant".equals(role) && content.length() > HISTORY_ASSISTANT_CONTENT_MAX) {
                content = content.substring(0, HISTORY_ASSISTANT_CONTENT_MAX) + "...";
            }
            if ("user".equals(role)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("用户：").append(content);
            } else if ("assistant".equals(role)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("AI：").append(content);
            }
        }
        return sb.toString();
    }

    private Text2SqlResult validateAndExecute(String question,
                                               String datasourceId,
                                               String sessionId,
                                               String systemPrompt,
                                               String userPrompt,
                                               String rawSql,
                                               List<ReasoningStep> steps,
                                               StepConsumer consumer,
                                               long pipelineStart,
                                               boolean retried,
                                               boolean scriptMode,
                                               Set<String> allowedTables) {
        if (scriptMode) {
            return validateAndExecuteScript(question, datasourceId, sessionId, systemPrompt, userPrompt,
                    rawSql, steps, consumer, pipelineStart, retried, allowedTables);
        }
        return validateAndExecuteSimple(question, datasourceId, sessionId, systemPrompt, userPrompt,
                rawSql, steps, consumer, pipelineStart, retried, allowedTables);
    }

    private Text2SqlResult validateAndExecuteSimple(String question,
                                                      String datasourceId,
                                                      String sessionId,
                                                      String systemPrompt,
                                                      String userPrompt,
                                                      String rawSql,
                                                      List<ReasoningStep> steps,
                                                      StepConsumer consumer,
                                                      long pipelineStart,
                                                      boolean retried,
                                                      Set<String> allowedTables) {
        long t0 = System.currentTimeMillis();
        SqlGuardResult guard = sqlGuard.validateSimple(rawSql, allowedTables);
        long guardMs = System.currentTimeMillis() - t0;
        if (!guard.isAllowed()) {
            emit(steps, consumer, "安全校验", "不通过: " + guard.getReason(), guardMs);
            if (!retried) {
                return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
                        rawSql, guard.getReason(), steps, consumer, pipelineStart, false, allowedTables);
            }
            Text2SqlResult fail = Text2SqlResult.fail(question, guard.getReason(), steps,
                    System.currentTimeMillis() - pipelineStart);
            fail.setRawSql(rawSql);
            fail.setScriptMode(false);
            return fail;
        }
        String normalizedSql = guard.getNormalizedSql();
        emit(steps, consumer, "安全校验", "通过，normalizedSql 已就绪", guardMs);

        t0 = System.currentTimeMillis();
        SqlExecuteResult exec = sqlExecutor.execute(datasourceId, normalizedSql);
        long execMs = System.currentTimeMillis() - t0;

        if (exec.isSuccess()) {
            emit(steps, consumer, "执行",
                    "成功，返回 " + exec.getRowCount() + " 行" + (exec.isTruncated() ? "（已截断）" : ""),
                    execMs);
            fewShotService.trySave(datasourceId, sessionId, question, normalizedSql, exec.getRowCount());
            try {
                relationUsageService.recordUsage(
                        datasourceId,
                        sessionId,
                        questionHash(question),
                        normalizedSql,
                        new UsageContext(true, exec.getRowCount(), false, false));
            } catch (Exception ex) {
                log.warn("[Sidekick][text2sql] relation usage record skipped: {}", ex.getMessage());
            }
            Text2SqlResult ok = new Text2SqlResult();
            ok.setQuestion(question);
            ok.setSql(normalizedSql);
            ok.setRawSql(rawSql);
            ok.setColumns(exec.getColumns());
            ok.setRows(exec.getRows());
            ok.setRowCount(exec.getRowCount());
            ok.setTruncated(exec.isTruncated());
            ok.setReasoningSteps(steps);
            ok.setSuccess(true);
            ok.setScriptMode(false);
            ok.setElapsedMs(System.currentTimeMillis() - pipelineStart);
            return ok;
        }

        String err = exec.getError() == null ? "执行失败" : exec.getError();
        emit(steps, consumer, "执行", "失败: " + err, execMs);

        if (!retried) {
            return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
                    normalizedSql, err, steps, consumer, pipelineStart, false, allowedTables);
        }

        Text2SqlResult fail = Text2SqlResult.fail(question, err, steps,
                System.currentTimeMillis() - pipelineStart);
        fail.setSql(normalizedSql);
        fail.setRawSql(rawSql);
        fail.setScriptMode(false);
        return fail;
    }

    private Text2SqlResult validateAndExecuteScript(String question,
                                                      String datasourceId,
                                                      String sessionId,
                                                      String systemPrompt,
                                                      String userPrompt,
                                                      String rawSql,
                                                      List<ReasoningStep> steps,
                                                      StepConsumer consumer,
                                                      long pipelineStart,
                                                      boolean retried,
                                                      Set<String> allowedTables) {
        long t0 = System.currentTimeMillis();
        ScriptGuardResult guard = sqlGuard.validateScript(rawSql, true, allowedTables);
        long guardMs = System.currentTimeMillis() - t0;
        if (!guard.isAllowed()) {
            String reason = guard.getReason() == null ? "安全校验未通过" : guard.getReason();
            emit(steps, consumer, "安全校验", "不通过: " + reason, guardMs);
            if (!retried) {
                return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
                        rawSql, reason, steps, consumer, pipelineStart, true, allowedTables);
            }
            Text2SqlResult fail = Text2SqlResult.fail(question, reason, steps,
                    System.currentTimeMillis() - pipelineStart);
            fail.setRawSql(rawSql);
            fail.setScriptMode(true);
            return fail;
        }
        emit(steps, consumer, "安全校验",
                "通过，共 " + guard.getStatements().size() + " 条语句", guardMs);
        emit(steps, consumer, "脚本模式",
                "生成 " + guard.getStatements().size() + " 条语句", 0);

        t0 = System.currentTimeMillis();
        ScriptExecuteResult exec = scriptExecutor.executeValidated(datasourceId, guard);
        long execMs = System.currentTimeMillis() - t0;

        if (exec.isSuccess() && exec.getFinalResult() != null) {
            ScriptStepResult finalStep = exec.getFinalResult();
            emit(steps, consumer, "执行",
                    "成功，返回 " + finalStep.getRowCount() + " 行"
                            + (finalStep.isTruncated() ? "（已截断）" : "")
                            + "，步骤 " + exec.getSteps().size(),
                    execMs);
            String script = exec.getScript();
            fewShotService.trySave(datasourceId, sessionId, question, script, finalStep.getRowCount());
            try {
                String relationSql = lastSelectStatement(guard.getStatements());
                relationUsageService.recordUsage(
                        datasourceId,
                        sessionId,
                        questionHash(question),
                        StringUtils.hasText(relationSql) ? relationSql : script,
                        new UsageContext(true, finalStep.getRowCount(), false, false));
            } catch (Exception ex) {
                log.warn("[Sidekick][text2sql] relation usage record skipped: {}", ex.getMessage());
            }
            Text2SqlResult ok = new Text2SqlResult();
            ok.setQuestion(question);
            ok.setSql(script);
            ok.setRawSql(rawSql);
            ok.setColumns(finalStep.getColumns());
            ok.setRows(finalStep.getRows());
            ok.setRowCount(finalStep.getRowCount());
            ok.setTruncated(finalStep.isTruncated());
            ok.setReasoningSteps(steps);
            ok.setSuccess(true);
            ok.setScriptMode(true);
            ok.setScriptSteps(exec.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
            ok.setElapsedMs(System.currentTimeMillis() - pipelineStart);
            return ok;
        }

        String err = exec.getError() == null ? "执行失败" : exec.getError();
        emit(steps, consumer, "执行", "失败: " + err, execMs);

        if (!retried) {
            return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt,
                    guard.getNormalizedScript(), err, steps, consumer, pipelineStart, true, allowedTables);
        }

        Text2SqlResult fail = Text2SqlResult.fail(question, err, steps,
                System.currentTimeMillis() - pipelineStart);
        fail.setSql(guard.getNormalizedScript());
        fail.setRawSql(rawSql);
        fail.setScriptMode(true);
        if (exec.getSteps() != null) {
            fail.setScriptSteps(exec.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
        }
        return fail;
    }

    private Text2SqlResult retryOnce(String question,
                                      String datasourceId,
                                      String sessionId,
                                      String systemPrompt,
                                      String userPrompt,
                                      String previousSql,
                                      String err,
                                      List<ReasoningStep> steps,
                                      StepConsumer consumer,
                                      long pipelineStart,
                                      boolean scriptMode,
                                      Set<String> allowedTables) {
        log.warn("[Sidekick][text2sql] 执行失败，尝试重试一次: {}", err);
        long retryStart = System.currentTimeMillis();
        try {
            String retryPrompt = promptBuilder.buildRetryUserPrompt(previousSql, err);
            String combinedUser = userPrompt + "\n\n" + retryPrompt;
            String retryRaw = prepareLlmSql(deepSeekClient.chat(systemPrompt, combinedUser), scriptMode);
            emit(steps, consumer, "生成SQL(重试)", preview(retryRaw, 100),
                    System.currentTimeMillis() - retryStart);
            if (StringUtils.hasText(retryRaw)) {
                boolean effectiveScript = scriptMode && looksLikeMultiStatement(retryRaw);
                return validateAndExecute(question, datasourceId, sessionId, systemPrompt, userPrompt,
                        retryRaw, steps, consumer, pipelineStart, true, effectiveScript, allowedTables);
            }
        } catch (Exception ex) {
            emit(steps, consumer, "生成SQL(重试)", "失败: " + ex.getMessage(),
                    System.currentTimeMillis() - retryStart);
        }
        Text2SqlResult fail = Text2SqlResult.fail(question, err, steps,
                System.currentTimeMillis() - pipelineStart);
        fail.setSql(previousSql);
        fail.setScriptMode(scriptMode);
        return fail;
    }

    private static void emit(List<ReasoningStep> steps, StepConsumer consumer,
                             String phase, String content, long elapsedMs) {
        steps.add(new ReasoningStep(phase, content, elapsedMs));
        if (consumer != null) {
            consumer.onStep(phase, content, elapsedMs);
        }
    }

    private String resolveDialect(String datasourceId, DbVersionInfo versionInfo) {
        if (versionInfo != null && StringUtils.hasText(versionInfo.getProduct())) {
            String p = versionInfo.getProduct().toLowerCase(Locale.ROOT);
            if (p.contains("postgres")) {
                return "PostgreSQL";
            }
            if (p.contains("mysql") || p.contains("mariadb")) {
                return "MySQL";
            }
        }
        try {
            DbConfig config = dataSourceManager.getConfig(datasourceId);
            if (config != null && config.getType() == DbType.POSTGRESQL) {
                return "PostgreSQL";
            }
        } catch (Exception ex) {
            log.debug("[Sidekick][text2sql] resolve dialect fallback MySQL: {}", ex.getMessage());
        }
        return "MySQL";
    }

    /** 原问题 MD5 的前 8 位，用作同一会话内去重。 */
    public static String questionHash(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(question.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 8);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 单条模式先抽出 SQL 再去掉末尾分号。脚本模式保持原清洗，避免切掉前面的临时表语句。
     */
    static String prepareLlmSql(String raw, boolean scriptMode) {
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

    static String cleanSql(String raw) {
        return cleanSql(raw, false);
    }

    /** 脚本只取最后一条 SELECT/WITH，中间临时表 DDL 不计入业务关系。 */
    static String lastSelectStatement(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return null;
        }
        String last = null;
        for (String s : statements) {
            if (!StringUtils.hasText(s)) {
                continue;
            }
            String t = s.trim();
            String upper = t.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
                last = t;
            }
        }
        return last;
    }

    static String cleanSql(String raw, boolean scriptMode) {
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

    static boolean looksLikeMultiStatement(String sql) {
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

    static boolean looksDestructive(String question) {
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

    private static String preview(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim().replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
