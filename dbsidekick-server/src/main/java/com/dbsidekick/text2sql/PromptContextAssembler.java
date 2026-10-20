package com.dbsidekick.text2sql;

import com.dbsidekick.ai.hybrid.HybridRetrievalService;
import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.asset.ChatSessionService;
import com.dbsidekick.asset.MessageDetail;
import com.dbsidekick.config.UserFacingErrors;
import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DbType;
import com.dbsidekick.datasource.DbVersionInfo;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.dbsidekick.relation.RelationUsageService;
import com.dbsidekick.schema.SchemaSyncService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 组装历史、改写、Schema 检索、Few-shot 与最终 Prompt。
 * 流式链路会推送改写 / 检索 / 示例事件；诊断链路不推送、不标记示例已使用。
 */
@Component
public class PromptContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptContextAssembler.class);
    private static final int HISTORY_MESSAGE_LIMIT = 6;
    private static final int HISTORY_ASSISTANT_CONTENT_MAX = 200;
    private static final int HISTORY_TOTAL_MAX = 1500;

    private final ChatSessionService chatSessionService;
    private final QueryRewriteService queryRewriteService;
    private final HybridRetrievalService hybridRetrievalService;
    private final FewShotService fewShotService;
    private final RelationUsageService relationUsageService;
    private final SchemaSyncService schemaSyncService;
    private final DynamicDataSourceManager dataSourceManager;
    private final PromptBuilder promptBuilder;
    private final QueryComplexityAnalyzer complexityAnalyzer;

    public PromptContextAssembler(ChatSessionService chatSessionService,
                                  QueryRewriteService queryRewriteService,
                                  HybridRetrievalService hybridRetrievalService,
                                  FewShotService fewShotService,
                                  RelationUsageService relationUsageService,
                                  SchemaSyncService schemaSyncService,
                                  DynamicDataSourceManager dataSourceManager,
                                  PromptBuilder promptBuilder,
                                  QueryComplexityAnalyzer complexityAnalyzer) {
        this.chatSessionService = chatSessionService;
        this.queryRewriteService = queryRewriteService;
        this.hybridRetrievalService = hybridRetrievalService;
        this.fewShotService = fewShotService;
        this.relationUsageService = relationUsageService;
        this.schemaSyncService = schemaSyncService;
        this.dataSourceManager = dataSourceManager;
        this.promptBuilder = promptBuilder;
        this.complexityAnalyzer = complexityAnalyzer;
    }

    /**
     * 流式查询的前半段。检索失败或没有命中表时，失败结果里已经带上本轮 steps。
     */
    public AssemblyResult assembleStream(String datasourceId, String question, String sessionId, int topK,
                                         boolean scriptMode, ReasoningTrace trace, long pipelineStart) {
        String historyText = buildHistoryText(sessionId);
        List<MessageDetail> rewriteHistory = loadRewriteHistory(sessionId);

        long t0 = System.currentTimeMillis();
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(datasourceId, question, rewriteHistory);
        long rewriteMs = System.currentTimeMillis() - t0;
        String searchQuery = searchQuery(rewrite, question);
        String queryForFewShot = fewShotQuery(rewrite, question);
        emitRewrite(trace, rewrite, searchQuery, rewriteMs);

        t0 = System.currentTimeMillis();
        List<SchemaHit> hits;
        try {
            hits = hybridRetrievalService.search(datasourceId, searchQuery, topK);
        } catch (Exception ex) {
            String friendly = UserFacingErrors.of(ex);
            trace.emit("检索Schema", "检索失败: " + friendly, System.currentTimeMillis() - t0);
            return AssemblyResult.fail(Text2SqlResult.fail(question, friendly, trace.steps(),
                    System.currentTimeMillis() - pipelineStart));
        }
        long searchMs = System.currentTimeMillis() - t0;
        if (hits == null || hits.isEmpty()) {
            trace.emit("检索Schema", "未检索到相关表", searchMs);
            return AssemblyResult.fail(Text2SqlResult.fail(question, "未检索到相关表", trace.steps(),
                    System.currentTimeMillis() - pipelineStart));
        }
        String tableNames = hits.stream()
                .map(SchemaHit::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(", "));
        trace.emit("检索Schema", "命中 " + hits.size() + " 张表：" + tableNames, searchMs);

        t0 = System.currentTimeMillis();
        List<String> candidateTables = hits.stream()
                .map(SchemaHit::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        Set<String> allowedTables = new LinkedHashSet<>(candidateTables);
        Set<String> neighborTables = relationUsageService.listConfirmedNeighborTables(datasourceId, candidateTables);
        if (!neighborTables.isEmpty()) {
            allowedTables.addAll(neighborTables);
            log.info("[Sidekick] allowedTables +confirmed neighbors: {}", String.join(", ", neighborTables));
        }
        List<FewShotExample> fewShots = List.of();
        if (fewShotService.isEnabled()) {
            fewShots = fewShotService.findSimilar(datasourceId, queryForFewShot, candidateTables, 0);
            if (fewShots.isEmpty()) {
                trace.emit("检索示例", "无相似示例", System.currentTimeMillis() - t0);
            } else {
                trace.emit("检索示例",
                        "找到 " + fewShots.size() + " 个相似示例（来源：历史成功查询）",
                        System.currentTimeMillis() - t0);
                fewShotService.markUsed(fewShots);
            }
        }

        BuiltPrompt prompts = buildPrompts(datasourceId, question, hits, historyText, fewShots, scriptMode);
        return AssemblyResult.ok(new PromptAssembly(
                prompts.systemPrompt, prompts.userPrompt, historyText, hits, fewShots, allowedTables));
    }

    /**
     * 诊断用：与流式链路同序调用改写、检索和 Prompt 组装，不推送、不执行、不 markUsed。
     */
    public Map<String, Object> assembleDebug(String datasourceId, String question, String sessionId, int topK) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("question", question);
        out.put("sessionId", sessionId);

        List<Map<String, String>> historyMessages = loadHistoryMessages(sessionId);
        out.put("historyMessages", historyMessages);

        List<MessageDetail> rewriteHistory = loadRewriteHistory(sessionId);
        QueryRewriteService.RewriteResult rewrite = queryRewriteService.rewrite(datasourceId, question, rewriteHistory);
        String rewritten = searchQuery(rewrite, question);
        out.put("rewrittenQuery", rewritten);
        out.put("rewriteUsedHistory", rewrite.isUsedHistory());
        out.put("followUp", rewrite.isFollowUp() || complexityAnalyzer.isFollowUp(question));
        out.put("rewriteFallback", rewrite.isFallback());

        List<SchemaHit> hits = List.of();
        try {
            hits = hybridRetrievalService.search(datasourceId, rewritten, topK);
        } catch (Exception ex) {
            log.warn("[Sidekick][debug-prompt] schema search failed: {}", ex.getMessage());
        }
        List<String> schemaHitNames = hits == null ? List.of() : hits.stream()
                .map(SchemaHit::getTableName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        out.put("schemaHits", schemaHitNames);

        String queryForFewShot = fewShotQuery(rewrite, question);
        List<String> candidateTables = new ArrayList<>(schemaHitNames);
        List<FewShotExample> fewShots = List.of();
        if (fewShotService.isEnabled()) {
            fewShots = fewShotService.findSimilar(datasourceId, queryForFewShot, candidateTables, 0);
        }
        List<Map<String, Object>> fewShotMaps = new ArrayList<>();
        for (FewShotExample ex : fewShots) {
            if (ex == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ex.getId());
            row.put("question", ex.getQuestion());
            row.put("sqlText", ex.getSqlText());
            row.put("tablesUsed", ex.getTablesUsed());
            row.put("score", ex.getScore());
            fewShotMaps.add(row);
        }
        out.put("fewShotExamples", fewShotMaps);
        out.put("fewShotSearchQuery", queryForFewShot);

        String historyText = buildHistoryText(sessionId);
        boolean scriptMode = complexityAnalyzer.needsScriptMode(question);
        BuiltPrompt prompts = buildPrompts(datasourceId, question, hits, historyText, fewShots, scriptMode);
        out.put("finalSystemPrompt", prompts.systemPrompt);
        out.put("finalUserPrompt", prompts.userPrompt);

        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("rewriteSignature", "rewrite(datasourceId, question, historyMessages)");
        notes.put("rewriteHistorySize", rewriteHistory.size());
        notes.put("historyInjectedIntoUserPrompt", StringUtils.hasText(historyText));
        notes.put("userPromptContainsHistorySection",
                prompts.userPrompt != null && prompts.userPrompt.contains("对话历史"));
        notes.put("fewShotBasedOn", rewrite.isFallback() ? "question (fallback)" : "rewrittenQuery");
        notes.put("scriptMode", scriptMode);
        out.put("diagnostics", notes);
        return out;
    }

    private void emitRewrite(ReasoningTrace trace, QueryRewriteService.RewriteResult rewrite,
                             String searchQuery, long rewriteMs) {
        if (rewrite.isFallback()) {
            trace.emit("改写问题",
                    "改写失败，使用原问题" + (rewrite.isFromCache() ? "（缓存）" : "")
                            + (rewrite.isUsedHistory() ? "（含历史）" : ""),
                    rewriteMs);
            return;
        }
        String hitsHint = rewrite.getHitTables() == null || rewrite.getHitTables().isEmpty()
                ? ""
                : "（命中候选表：" + String.join(", ", rewrite.getHitTables()) + "）";
        trace.emit("改写问题",
                "改写为：" + searchQuery + hitsHint + (rewrite.isFromCache() ? "（缓存）" : "")
                        + (rewrite.isUsedHistory() ? "（含历史）" : "")
                        + (rewrite.isFollowUp() ? "（追问）" : ""),
                rewriteMs);
    }

    private BuiltPrompt buildPrompts(String datasourceId, String question, List<SchemaHit> hits,
                                     String historyText, List<FewShotExample> fewShots, boolean scriptMode) {
        DbVersionInfo versionInfo = resolveVersion(datasourceId);
        String dialect = resolveDialect(datasourceId, versionInfo);
        String systemPrompt = scriptMode
                ? promptBuilder.buildScriptSystemPrompt(versionInfo)
                : promptBuilder.buildSystemPrompt(dialect, versionInfo);
        String userPrompt = promptBuilder.buildUserPrompt(question, hits, historyText, fewShots, scriptMode);
        return new BuiltPrompt(systemPrompt, userPrompt);
    }

    private static String searchQuery(QueryRewriteService.RewriteResult rewrite, String question) {
        return StringUtils.hasText(rewrite.getRewrittenQuery()) ? rewrite.getRewrittenQuery() : question;
    }

    private static String fewShotQuery(QueryRewriteService.RewriteResult rewrite, String question) {
        if (!rewrite.isFallback() && StringUtils.hasText(rewrite.getRewrittenQuery())) {
            return rewrite.getRewrittenQuery();
        }
        return question;
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
            for (MessageDetail message : recent) {
                if (message == null || !StringUtils.hasText(message.getRole())) {
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                row.put("role", message.getRole().trim().toLowerCase(Locale.ROOT));
                row.put("content", message.getContent() == null ? "" : message.getContent());
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
        for (MessageDetail message : messages) {
            if (message == null || !StringUtils.hasText(message.getRole())) {
                continue;
            }
            String role = message.getRole().trim().toLowerCase();
            String content = message.getContent() == null ? "" : message.getContent().trim();
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

    private String resolveDialect(String datasourceId, DbVersionInfo versionInfo) {
        if (versionInfo != null && StringUtils.hasText(versionInfo.getProduct())) {
            String product = versionInfo.getProduct().toLowerCase(Locale.ROOT);
            if (product.contains("postgres")) {
                return "PostgreSQL";
            }
            if (product.contains("mysql") || product.contains("mariadb")) {
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

    private record BuiltPrompt(String systemPrompt, String userPrompt) {
    }
}
