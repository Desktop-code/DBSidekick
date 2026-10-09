package com.dbsidekick.ai.hybrid;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.ai.milvus.SchemaHit;
import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.schema.SchemaVectorService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 混合检索：Dense + Sparse → RRF →（可选）LLM Rerank。
 */
@Service
public class HybridRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrievalService.class);
    private static final int RRF_K = 60;
    private static final float DENSE_WEIGHT = 1.0f;
    private static final float SPARSE_WEIGHT = 0.6f;
    private static final int SPARSE_TOP_K = 5;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s,，、;；|/\\\\._\\-]+");

    private final SchemaVectorService schemaVectorService;
    private final RerankService rerankService;
    private final SidekickAiProperties aiProperties;
    private final SqliteInitializer sqliteInitializer;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public HybridRetrievalService(@Lazy SchemaVectorService schemaVectorService,
                                  RerankService rerankService,
                                  SidekickAiProperties aiProperties,
                                  SqliteInitializer sqliteInitializer) {
        this.schemaVectorService = schemaVectorService;
        this.rerankService = rerankService;
        this.aiProperties = aiProperties;
        this.sqliteInitializer = sqliteInitializer;
    }

    public void clearCache() {
        cache.clear();
        rerankService.clearCache();
        log.info("[Sidekick][hybrid] cache cleared");
    }

    public List<SchemaHit> search(String datasourceId, String query, int topK) {
        SearchBundle bundle = searchDetailed(datasourceId, query, -1, topK);
        return bundle.reranked();
    }

    /**
     * 诊断用：返回候选与精排结果。
     */
    public SearchBundle searchDetailed(String datasourceId, String query, int candidateTopK, int finalTopK) {
        long start = System.currentTimeMillis();
        SidekickAiProperties.Rerank cfg = rerankCfg();
        int candK = candidateTopK > 0 ? candidateTopK : Math.max(1, cfg.getCandidateTopK());
        int finK = finalTopK > 0 ? finalTopK : Math.max(1, cfg.getFinalTopK());

        String ds = datasourceId == null ? "" : datasourceId.trim();
        String q = query == null ? "" : query.trim();
        String activeDb = com.dbsidekick.datasource.ActiveDatabase.get();
        String cacheKey = ds + "\n" + (activeDb == null ? "" : activeDb.toLowerCase(Locale.ROOT))
                + "\n" + q.toLowerCase(Locale.ROOT) + "\n" + candK + "\n" + finK;
        long ttl = cfg.getCacheTtlMs() > 0 ? cfg.getCacheTtlMs() : 60_000L;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.atMs < ttl) {
            log.info("[Sidekick][hybrid] cache hit elapsedMs={}", System.currentTimeMillis() - start);
            return cached.bundle.withCacheFlag(true);
        }

        // dense 主导 Top10；sparse 只取 Top5，降低杂表噪音
        List<SchemaHit> dense = schemaVectorService.search(ds, q, candK);
        List<SchemaHit> sparse = sparseSearch(ds, q, Math.min(SPARSE_TOP_K, candK));
        List<SchemaHit> fused = rrfFuse(dense, sparse, candK);

        List<SchemaHit> reranked;
        if (cfg.isEnabled()) {
            reranked = rerankService.rerank(ds, q, fused, finK);
        } else {
            reranked = fused.size() <= finK ? new ArrayList<>(fused) : new ArrayList<>(fused.subList(0, finK));
        }
        schemaVectorService.enrichHitContent(ds, fused);
        schemaVectorService.enrichHitContent(ds, reranked);

        SearchBundle bundle = new SearchBundle(dense, sparse, fused, reranked, false);
        cache.put(cacheKey, new CacheEntry(bundle, System.currentTimeMillis()));

        log.info("[Sidekick][hybrid] dense={} sparse={} fused={} reranked={} enabled={} elapsedMs={}",
                dense.size(), sparse.size(), fused.size(), reranked.size(),
                cfg.isEnabled(), System.currentTimeMillis() - start);
        return bundle;
    }

    private List<SchemaHit> sparseSearch(String datasourceId, String query, int topK) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty() || !StringUtils.hasText(datasourceId)) {
            return List.of();
        }
        List<ScoredTable> scored = new ArrayList<>();
        String sql = """
                SELECT table_name, db_name, table_comment, table_aliases, table_purpose, ddl_text
                FROM schema_table WHERE datasource_id = ?
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    String dbName = rs.getString("db_name");
                    String activeDb = com.dbsidekick.datasource.ActiveDatabase.get();
                    if (StringUtils.hasText(activeDb)
                            && (dbName == null || !activeDb.equalsIgnoreCase(dbName))) {
                        continue;
                    }
                    String hay = (nullToEmpty(name) + " "
                            + nullToEmpty(rs.getString("table_comment")) + " "
                            + nullToEmpty(rs.getString("table_aliases")) + " "
                            + nullToEmpty(rs.getString("table_purpose")) + " "
                            + nullToEmpty(rs.getString("ddl_text"))).toLowerCase(Locale.ROOT);
                    float score = 0f;
                    for (String tok : tokens) {
                        if (hay.contains(tok)) {
                            score += tok.length() >= 2 ? 2f : 1f;
                            if (name != null && name.toLowerCase(Locale.ROOT).contains(tok)) {
                                score += 3f;
                            }
                        }
                    }
                    if (score > 0) {
                        scored.add(new ScoredTable(name, dbName, score));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][hybrid] sparse failed: {}", ex.getMessage());
            return List.of();
        }
        scored.sort(Comparator.comparingDouble(ScoredTable::score).reversed());
        List<SchemaHit> hits = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            ScoredTable t = scored.get(i);
            // L2 风格：分数越小越好，这里转成伪距离便于统一展示
            float dist = 1f / (1f + t.score);
            hits.add(new SchemaHit(t.tableName, t.dbName, "", dist));
        }
        return hits;
    }

    private static List<SchemaHit> rrfFuse(List<SchemaHit> dense, List<SchemaHit> sparse, int topK) {
        Map<String, Float> scores = new HashMap<>();
        Map<String, SchemaHit> best = new LinkedHashMap<>();

        addRrf(scores, best, dense, DENSE_WEIGHT);
        addRrf(scores, best, sparse, SPARSE_WEIGHT);

        List<Map.Entry<String, Float>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));

        List<SchemaHit> out = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, ranked.size()); i++) {
            String name = ranked.get(i).getKey();
            SchemaHit src = best.get(name);
            float rrf = ranked.get(i).getValue();
            // 伪 L2：RRF 越高越好 → 距离越小
            float dist = 1f / (1f + rrf);
            out.add(new SchemaHit(src.getTableName(), src.getDbName(),
                    src.getContent() == null ? "" : src.getContent(), dist));
        }
        // 优先用 dense 的 content（卡片文本）
        Map<String, String> denseContent = new HashMap<>();
        for (SchemaHit h : dense) {
            if (h != null && StringUtils.hasText(h.getTableName()) && StringUtils.hasText(h.getContent())) {
                denseContent.put(h.getTableName(), h.getContent());
            }
        }
        for (SchemaHit h : out) {
            String c = denseContent.get(h.getTableName());
            if (StringUtils.hasText(c)) {
                h.setContent(c);
            }
        }
        return out;
    }

    private static void addRrf(Map<String, Float> scores, Map<String, SchemaHit> best,
                               List<SchemaHit> hits, float weight) {
        if (hits == null) {
            return;
        }
        for (int i = 0; i < hits.size(); i++) {
            SchemaHit h = hits.get(i);
            if (h == null || !StringUtils.hasText(h.getTableName())) {
                continue;
            }
            float add = weight * (1f / (RRF_K + i + 1));
            scores.merge(h.getTableName(), add, Float::sum);
            best.putIfAbsent(h.getTableName(), h);
            // dense 优先覆盖 content
            if (StringUtils.hasText(h.getContent())) {
                best.put(h.getTableName(), h);
            }
        }
    }

    private static List<String> tokenize(String query) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(query)) {
            return out;
        }
        String q = query.toLowerCase(Locale.ROOT);
        for (String part : TOKEN_SPLIT.split(q)) {
            String t = part.trim();
            if (t.length() >= 1) {
                out.add(t);
            }
        }
        // 中文 bigram 弱增强
        String cn = q.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i + 1 < cn.length(); i++) {
            out.add(cn.substring(i, i + 2));
        }
        return out;
    }

    private SidekickAiProperties.Rerank rerankCfg() {
        return aiProperties.getRerank() == null ? new SidekickAiProperties.Rerank() : aiProperties.getRerank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record ScoredTable(String tableName, String dbName, float score) {
    }

    private record CacheEntry(SearchBundle bundle, long atMs) {
    }

    public record SearchBundle(List<SchemaHit> dense,
                               List<SchemaHit> sparse,
                               List<SchemaHit> candidates,
                               List<SchemaHit> reranked,
                               boolean fromCache) {
        public SearchBundle withCacheFlag(boolean flag) {
            return new SearchBundle(dense, sparse, candidates, reranked, flag);
        }
    }
}
