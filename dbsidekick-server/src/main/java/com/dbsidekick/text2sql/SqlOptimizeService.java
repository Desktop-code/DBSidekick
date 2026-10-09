package com.dbsidekick.text2sql;

import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DbType;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.dbsidekick.schema.SchemaVectorService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 优化 SQL / 解释 SQL（不执行）。
 */
@Service
public class SqlOptimizeService {

    private static final Logger log = LoggerFactory.getLogger(SqlOptimizeService.class);

    private final OpenAiCompatibleClient deepSeekClient;
    private final DynamicDataSourceManager dataSourceManager;
    private final SchemaVectorService schemaVectorService;
    private final ObjectMapper objectMapper;

    public SqlOptimizeService(OpenAiCompatibleClient deepSeekClient,
                              DynamicDataSourceManager dataSourceManager,
                              SchemaVectorService schemaVectorService,
                              ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.dataSourceManager = dataSourceManager;
        this.schemaVectorService = schemaVectorService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> optimize(String datasourceId, String sql) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            requireConfigured(datasourceId, sql);
            String dialect = dialectOf(datasourceId);
            String schemaHint = schemaHint(datasourceId, sql);

            String system = "你是 SQL 优化专家。针对 " + dialect
                    + " 方言优化以下 SQL。返回 JSON: {\"sql\": \"优化后的SQL\", \"changes\": [\"改动1\",\"改动2\"]}。"
                    + "只输出 JSON，不要 markdown。";
            String user = "原始 SQL:\n" + sql.trim()
                    + (StringUtils.hasText(schemaHint) ? "\n\n相关 Schema:\n" + schemaHint : "");

            String raw = deepSeekClient.chat(system, user);
            String optimized = sql.trim();
            List<String> changes = new ArrayList<>();
            try {
                String json = extractJson(raw);
                JsonNode root = objectMapper.readTree(json);
                if (root.has("sql") && StringUtils.hasText(root.path("sql").asText())) {
                    optimized = root.path("sql").asText().trim();
                }
                JsonNode ch = root.path("changes");
                if (ch.isArray()) {
                    for (JsonNode n : ch) {
                        if (n != null && StringUtils.hasText(n.asText())) {
                            changes.add(n.asText().trim());
                        }
                    }
                }
            } catch (Exception parseEx) {
                log.warn("[Sidekick] optimize JSON parse failed, use raw: {}", parseEx.getMessage());
                optimized = stripMarkdownFence(raw);
                changes = List.of();
            }
            body.put("success", true);
            body.put("optimizedSql", optimized);
            body.put("changes", changes);
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        } catch (Exception ex) {
            body.put("success", false);
            body.put("error", ex.getMessage());
            body.put("optimizedSql", sql == null ? "" : sql);
            body.put("changes", List.of());
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }
    }

    public Map<String, Object> explain(String datasourceId, String sql) {
        long start = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            requireConfigured(datasourceId, sql);
            String dialect = dialectOf(datasourceId);
            String system = "你是 SQL 讲师。用中文简明解释这条 " + dialect
                    + " SQL 在做什么，包括：查了哪些表、过滤条件、聚合/排序逻辑、返回什么。200 字以内。"
                    + "不要用 markdown，直接输出纯文本。";
            String explanation = deepSeekClient.chat(system, sql.trim()).trim();
            body.put("success", true);
            body.put("explanation", explanation);
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        } catch (Exception ex) {
            body.put("success", false);
            body.put("error", ex.getMessage());
            body.put("explanation", "");
            body.put("elapsedMs", System.currentTimeMillis() - start);
            return body;
        }
    }

    private void requireConfigured(String datasourceId, String sql) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        if (!deepSeekClient.isConfigured()) {
            throw new IllegalStateException("请先配置 DEEPSEEK_API_KEY");
        }
        dataSourceManager.getConfig(datasourceId.trim());
    }

    private String dialectOf(String datasourceId) {
        DbConfig cfg = dataSourceManager.getConfig(datasourceId.trim());
        DbType type = cfg.getType() == null ? DbType.MYSQL : cfg.getType();
        return type == DbType.POSTGRESQL ? "PostgreSQL" : "MySQL";
    }

    private String schemaHint(String datasourceId, String sql) {
        try {
            List<SchemaHit> hits = schemaVectorService.search(datasourceId.trim(), sql, 5);
            if (hits == null || hits.isEmpty()) {
                return "";
            }
            return hits.stream()
                    .map(h -> "- " + h.getTableName() + ": " + preview(h.getContent(), 200))
                    .collect(Collectors.joining("\n"));
        } catch (Exception ex) {
            log.debug("[Sidekick] optimize schema hint skipped: {}", ex.getMessage());
            return "";
        }
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String extractJson(String raw) {
        String t = stripMarkdownFence(raw).trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private static String stripMarkdownFence(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
        }
        return t.trim();
    }
}
