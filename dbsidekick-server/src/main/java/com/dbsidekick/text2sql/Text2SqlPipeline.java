package com.dbsidekick.text2sql;

import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.config.UserFacingErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Text2SQL 流水线：校验、组装、生成、单轮执行，失败后最多重试一次。
 * phase 只经 {@link ReasoningTrace} 发出，顺序与拆分前一致。
 */
@Component
public class Text2SqlPipeline {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlPipeline.class);
    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final OpenAiCompatibleClient deepSeekClient;
    private final PromptContextAssembler promptContextAssembler;
    private final SqlExecutionFacade sqlExecutionFacade;
    private final PromptBuilder promptBuilder;
    private final QueryComplexityAnalyzer complexityAnalyzer;

    public Text2SqlPipeline(OpenAiCompatibleClient deepSeekClient,
                            PromptContextAssembler promptContextAssembler,
                            SqlExecutionFacade sqlExecutionFacade,
                            PromptBuilder promptBuilder,
                            QueryComplexityAnalyzer complexityAnalyzer) {
        this.deepSeekClient = deepSeekClient;
        this.promptContextAssembler = promptContextAssembler;
        this.sqlExecutionFacade = sqlExecutionFacade;
        this.promptBuilder = promptBuilder;
        this.complexityAnalyzer = complexityAnalyzer;
    }

    public Text2SqlResult streamQuery(String datasourceId, String question, int topK,
                                      String sessionId, StepConsumer consumer) {
        long pipelineStart = System.currentTimeMillis();
        List<ReasoningStep> steps = new ArrayList<>();
        ReasoningTrace trace = new ReasoningTrace(steps, consumer);

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
        int k = clampTopK(topK);

        long t0 = System.currentTimeMillis();
        boolean scriptMode = complexityAnalyzer.needsScriptMode(q);
        trace.emit("理解问题",
                "用户问题：" + q + (scriptMode ? "（脚本模式）" : ""),
                System.currentTimeMillis() - t0);
        if (LlmSqlNormalizer.looksDestructive(q)) {
            trace.emit("安全校验", "检测到危险意图，拒绝生成非 SELECT", 0);
            return Text2SqlResult.fail(q, "只允许 SELECT 查询", steps,
                    System.currentTimeMillis() - pipelineStart);
        }

        AssemblyResult assembled = promptContextAssembler.assembleStream(
                ds, q, sessionId, k, scriptMode, trace, pipelineStart);
        if (assembled.failed()) {
            return assembled.failure();
        }
        PromptAssembly context = assembled.assembly();

        t0 = System.currentTimeMillis();
        String rawSql;
        try {
            rawSql = LlmSqlNormalizer.prepareLlmSql(
                    deepSeekClient.chat(context.getSystemPrompt(), context.getUserPrompt()), scriptMode);
        } catch (IllegalArgumentException ex) {
            trace.emit("生成SQL", ex.getMessage(), System.currentTimeMillis() - t0);
            return Text2SqlResult.fail(q, ex.getMessage(), steps, System.currentTimeMillis() - pipelineStart);
        } catch (Exception ex) {
            String friendly = UserFacingErrors.of(ex);
            trace.emit("生成SQL", "LLM 调用失败: " + friendly, System.currentTimeMillis() - t0);
            return Text2SqlResult.fail(q, friendly, steps, System.currentTimeMillis() - pipelineStart);
        }
        long genMs = System.currentTimeMillis() - t0;
        if (!StringUtils.hasText(rawSql)) {
            trace.emit("生成SQL", "LLM 返回为空", genMs);
            return Text2SqlResult.fail(q, "LLM 返回为空", steps, System.currentTimeMillis() - pipelineStart);
        }
        trace.emit("生成SQL", LlmSqlNormalizer.preview(rawSql, 100), genMs);

        boolean effectiveScript = scriptMode && LlmSqlNormalizer.looksLikeMultiStatement(rawSql);
        if (scriptMode) {
            trace.emit("脚本模式",
                    effectiveScript ? "多语句脚本" : "判定为单条 SELECT，按简单模式执行",
                    0);
        }
        return executeWithRetry(q, ds, sessionId, context.getSystemPrompt(), context.getUserPrompt(),
                rawSql, trace, pipelineStart, effectiveScript, context.getAllowedTables());
    }

    /**
     * 诊断用：复现 Prompt 组装链路，不调用生成 SQL / 不执行 / 不 markUsed。
     */
    public Map<String, Object> debugPrompt(String datasourceId, String question, String sessionId, int topK) {
        String ds = datasourceId == null ? "" : datasourceId.trim();
        String q = question == null ? "" : question.trim();
        String sid = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
        return promptContextAssembler.assembleDebug(ds, q, sid, clampTopK(topK));
    }

    private Text2SqlResult executeWithRetry(String question, String datasourceId, String sessionId,
                                            String systemPrompt, String userPrompt, String rawSql,
                                            ReasoningTrace trace, long pipelineStart, boolean scriptMode,
                                            Set<String> allowedTables) {
        ExecutionPass pass = sqlExecutionFacade.execute(question, datasourceId, sessionId, rawSql, trace,
                pipelineStart, false, scriptMode, allowedTables);
        if (!pass.needsRetry()) {
            return pass.result();
        }
        return retryOnce(question, datasourceId, sessionId, systemPrompt, userPrompt, pass.sqlForRetry(),
                pass.error(), trace, pipelineStart, pass.scriptMode(), allowedTables);
    }

    private Text2SqlResult retryOnce(String question, String datasourceId, String sessionId,
                                     String systemPrompt, String userPrompt, String previousSql, String err,
                                     ReasoningTrace trace, long pipelineStart, boolean scriptMode,
                                     Set<String> allowedTables) {
        log.warn("[Sidekick][text2sql] 执行失败，尝试重试一次: {}", err);
        long retryStart = System.currentTimeMillis();
        try {
            String retryPrompt = promptBuilder.buildRetryUserPrompt(previousSql, err);
            String combinedUser = userPrompt + "\n\n" + retryPrompt;
            String retryRaw = LlmSqlNormalizer.prepareLlmSql(
                    deepSeekClient.chat(systemPrompt, combinedUser), scriptMode);
            trace.emit("生成SQL(重试)", LlmSqlNormalizer.preview(retryRaw, 100),
                    System.currentTimeMillis() - retryStart);
            if (StringUtils.hasText(retryRaw)) {
                boolean effectiveScript = scriptMode && LlmSqlNormalizer.looksLikeMultiStatement(retryRaw);
                ExecutionPass second = sqlExecutionFacade.execute(question, datasourceId, sessionId, retryRaw, trace,
                        pipelineStart, true, effectiveScript, allowedTables);
                return second.result();
            }
        } catch (Exception ex) {
            trace.emit("生成SQL(重试)", "失败: " + ex.getMessage(),
                    System.currentTimeMillis() - retryStart);
        }
        Text2SqlResult fail = Text2SqlResult.fail(question, err, trace.steps(),
                System.currentTimeMillis() - pipelineStart);
        fail.setSql(previousSql);
        fail.setScriptMode(scriptMode);
        return fail;
    }

    private static int clampTopK(int topK) {
        return topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
    }
}
