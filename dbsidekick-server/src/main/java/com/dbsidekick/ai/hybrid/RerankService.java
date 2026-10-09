package com.dbsidekick.ai.hybrid;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.config.SqliteInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * LLM 精排：对 Dense/混合召回的候选表按问题相关度重排。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);
    private static final double MIN_SCORE = 7.0;

    private static final String SYSTEM_PROMPT = """
            你是数据库检索排序助手。根据用户问题，从候选表列表中选出真正必需的表。
            返回 JSON: {"tables": [{"name": "表名", "score": 0-10}]}
            只输出 JSON，不要 markdown。

            评分标准：
            - 10 分：问题的核心表，必须查
            - 7-9 分：参与 JOIN 关联的表，需要
            - 4-6 分：可能相关但不必要
            - 0-3 分：不相关

            只返回 score >= 7 的表，最多 5 张，宁少勿多。
            如果问题只需要 1 张表，就只返回 1 张。
            不要为了凑数返回不相关的表。
            """.trim();

    private final OpenAiCompatibleClient deepSeekClient;
    private final SidekickAiProperties aiProperties;
    private final SqliteInitializer sqliteInitializer;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public RerankService(OpenAiCompatibleClient deepSeekClient,
                         SidekickAiProperties aiProperties,
                         SqliteInitializer sqliteInitializer,
                         ObjectMapper objectMapper) {
        this.deepSeekClient = deepSeekClient;
        this.aiProperties = aiProperties;
        this.sqliteInitializer = sqliteInitializer;
        this.objectMapper = objectMapper;
    }

    public void clearCache() {
        cache.clear();
        log.info("[Sidekick][rerank] cache cleared");
    }

    public List<SchemaHit> rerank(String datasourceId, String query, List<SchemaHit> candidates, int topK) {
        long start = System.currentTimeMillis();
        int k = topK <= 0 ? cfg().getFinalTopK() : topK;
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (!cfg().isEnabled()) {
            return take(candidates, k);
        }
        if (!deepSeekClient.isConfigured()) {
            return take(candidates, k);
        }

        String ds = datasourceId == null ? "" : datasourceId.trim();
        String q = query == null ? "" : query.trim();
        String cacheKey = ds + "\n" + q.toLowerCase(Locale.ROOT);
        CacheEntry cached = cache.get(cacheKey);
        long ttl = cfg().getCacheTtlMs() > 0 ? cfg().getCacheTtlMs() : 60_000L;
        if (cached != null && System.currentTimeMillis() - cached.atMs < ttl) {
            log.info("[Sidekick][rerank] cache hit query={} elapsedMs={}",
                    preview(q, 40), System.currentTimeMillis() - start);
            return mapScored(candidates, cached.scored, k);
        }

        try {
            String userPrompt = buildUserPrompt(ds, q, candidates);
            int timeout = Math.max(1, cfg().getTimeoutSeconds());
            String raw = deepSeekClient.chatWithTimeout(SYSTEM_PROMPT, userPrompt, timeout);
            List<ScoredTable> scored = parseScoredTables(raw);
            List<SchemaHit> ranked = mapScored(candidates, scored, k);
            if (ranked.isEmpty()) {
                log.warn("[Sidekick][rerank] empty after filter, fallback Top1");
                return take(candidates, 1);
            }
            cache.put(cacheKey, new CacheEntry(new ArrayList<>(scored), System.currentTimeMillis()));
            log.info("[Sidekick][rerank] ok tables={} elapsedMs={}",
                    ranked.stream().map(SchemaHit::getTableName).toList(),
                    System.currentTimeMillis() - start);
            return ranked;
        } catch (Exception ex) {
            log.warn("[Sidekick][rerank] fallback: {}", ex.getMessage());
            return take(candidates, Math.min(2, k));
        }
    }

    private String buildUserPrompt(String datasourceId, String query,
                                   List<SchemaHit> candidates) throws Exception {
        Map<String, TableMeta> meta = loadMeta(datasourceId);
        Map<String, String> relations = loadRelations(datasourceId);
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(query).append("\n\n候选表：\n");
        for (int i = 0; i < candidates.size(); i++) {
            SchemaHit hit = candidates.get(i);
            String name = hit.getTableName();
            TableMeta m = meta.get(name);
            String comment = m != null && StringUtils.hasText(m.comment) ? m.comment : "-";
            String purpose = m != null && StringUtils.hasText(m.purpose) ? m.purpose : "-";
            String rel = relations.getOrDefault(name, "无");
            sb.append(i + 1).append(". ").append(name)
                    .append(" | ").append(comment)
                    .append(" | 用途：").append(purpose)
                    .append(" | 关联：").append(rel)
                    .append('\n');
        }
        sb.append("\n请评分并返回：");
        return sb.toString();
    }

    private Map<String, TableMeta> loadMeta(String datasourceId) throws Exception {
        Map<String, TableMeta> map = new LinkedHashMap<>();
        if (!StringUtils.hasText(datasourceId)) {
            return map;
        }
        String sql = """
                SELECT table_name, table_comment, table_aliases, table_purpose
                FROM schema_table WHERE datasource_id = ?
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("table_name"), new TableMeta(
                            rs.getString("table_comment"),
                            rs.getString("table_aliases"),
                            rs.getString("table_purpose")));
                }
            }
        }
        return map;
    }

    /** tableName -> "col → ref; ..." */
    private Map<String, String> loadRelations(String datasourceId) throws Exception {
        Map<String, String> map = new LinkedHashMap<>();
        if (!StringUtils.hasText(datasourceId)) {
            return map;
        }
        String sql = """
                SELECT t.table_name, c.column_name, c.foreign_key_ref, c.column_comment
                FROM schema_table t
                JOIN schema_column c ON c.table_id = t.id
                WHERE t.datasource_id = ?
                  AND c.foreign_key_ref IS NOT NULL AND c.foreign_key_ref <> ''
                ORDER BY t.table_name, c.ordinal_position
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String col = rs.getString("column_name");
                    String ref = rs.getString("foreign_key_ref");
                    String piece = col + " → " + ref;
                    map.merge(table, piece, (a, b) -> a + "; " + b);
                }
            }
        }
        return map;
    }

    private List<ScoredTable> parseScoredTables(String raw) throws Exception {
        String json = stripFence(raw);
        JsonNode root = objectMapper.readTree(json);
        JsonNode arr = root.get("tables");
        List<ScoredTable> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null) {
                continue;
            }
            String name;
            double score;
            if (n.isTextual()) {
                // 兼容旧格式 ["sys_user", ...]
                name = n.asText().trim();
                score = 8.0;
            } else if (n.isObject()) {
                name = n.path("name").asText("").trim();
                if (!StringUtils.hasText(name)) {
                    name = n.path("table").asText("").trim();
                }
                score = n.path("score").asDouble(0);
            } else {
                continue;
            }
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (score < MIN_SCORE) {
                continue;
            }
            out.add(new ScoredTable(name, score));
        }
        out.sort(Comparator.comparingDouble(ScoredTable::score).reversed());
        return out;
    }

    /**
     * 只映射 LLM 返回且在候选中的表；不足 topK 也不从原候选补齐。
     */
    private static List<SchemaHit> mapScored(List<SchemaHit> candidates,
                                             List<ScoredTable> scored,
                                             int topK) {
        Map<String, SchemaHit> byName = new LinkedHashMap<>();
        for (SchemaHit h : candidates) {
            if (h != null && StringUtils.hasText(h.getTableName())) {
                byName.putIfAbsent(h.getTableName(), h);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<SchemaHit> out = new ArrayList<>();
        for (ScoredTable st : scored) {
            if (st == null || !StringUtils.hasText(st.name())) {
                continue;
            }
            if (st.score() < MIN_SCORE) {
                continue;
            }
            if (!seen.add(st.name())) {
                continue;
            }
            SchemaHit hit = byName.get(st.name());
            if (hit == null) {
                continue; // 幻觉表名忽略
            }
            // 用 LLM score 覆盖展示分（越高越好 → 转伪 L2）
            float dist = (float) (1.0 / (1.0 + st.score() / 10.0));
            out.add(new SchemaHit(hit.getTableName(), hit.getDbName(),
                    hit.getContent() == null ? "" : hit.getContent(), dist));
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    private static List<SchemaHit> take(List<SchemaHit> candidates, int topK) {
        if (candidates.size() <= topK) {
            return new ArrayList<>(candidates);
        }
        return new ArrayList<>(candidates.subList(0, topK));
    }

    private static String stripFence(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "{}";
        }
        String t = raw.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                t = t.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private SidekickAiProperties.Rerank cfg() {
        return aiProperties.getRerank() == null ? new SidekickAiProperties.Rerank() : aiProperties.getRerank();
    }

    private record TableMeta(String comment, String aliases, String purpose) {
    }

    private record ScoredTable(String name, double score) {
    }

    private record CacheEntry(List<ScoredTable> scored, long atMs) {
    }
}
