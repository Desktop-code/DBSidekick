package com.dbsidekick.text2sql;

import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.asset.MessageDetail;
import com.dbsidekick.config.SqliteInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 查询改写：用表清单 + 别名把口语问题改写成利于向量检索的文本。
 * 支持多轮追问：可带最近对话历史合并意图。
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);
    private static final int TIMEOUT_SECONDS = 3;
    private static final long CACHE_TTL_MS = 60_000L;
    private static final int HISTORY_TURNS = 2;
    private static final int HISTORY_MSG_LIMIT = HISTORY_TURNS * 2;
    private static final int HISTORY_CONTENT_MAX = 200;

    private static final String SYSTEM_PROMPT_BASE = """
            你是数据库查询改写助手。根据用户问题，从候选表清单中找出所有相关的表，
            生成一段面向语义检索的改写文本。
            改写文本要包含：涉及的表的真实表名 + 全部中文别名 + 原始问题的核心意图。
            返回 JSON：{"rewritten": "改写文本", "hitTables": ["表名1","表名2"]}
            只输出 JSON，不要 markdown。
            规则：
            - 优先选择能回答问题的表，不要贪多，2-4 张足够
            - 改写文本不要超过 50 字
            - 如果问题不涉及具体表（如问定义、闲聊），hitTables 返回空数组，rewritten 返回原文
            """.trim();

    private static final String SYSTEM_PROMPT_FOLLOW_UP = """

            如果用户的问题是**追问**（如'重新生成'、'带上注释'、'改成...'、'再...'、'这个'、
            '那个'、'它'、'刚才的'、'上面的'），说明它依赖上一轮对话。
            此时你应该结合【最近对话历史】理解用户的真实意图，把它和上一轮的问题合并改写成完整的检索 query。
            """.trim();

    private final SqliteInitializer sqliteInitializer;
    private final OpenAiCompatibleClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final QueryComplexityAnalyzer complexityAnalyzer;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public QueryRewriteService(SqliteInitializer sqliteInitializer,
                               OpenAiCompatibleClient deepSeekClient,
                               ObjectMapper objectMapper,
                               QueryComplexityAnalyzer complexityAnalyzer) {
        this.sqliteInitializer = sqliteInitializer;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
        this.complexityAnalyzer = complexityAnalyzer;
    }

    /** 兼容旧调用：无历史。 */
    public RewriteResult rewrite(String datasourceId, String originalQuery) {
        return rewrite(datasourceId, originalQuery, null);
    }

    public RewriteResult rewrite(String datasourceId, String originalQuery,
                                 List<MessageDetail> historyMessages) {
        long start = System.currentTimeMillis();
        String ds = datasourceId == null ? "" : datasourceId.trim();
        String q = originalQuery == null ? "" : originalQuery.trim();
        if (!StringUtils.hasText(ds) || !StringUtils.hasText(q)) {
            return RewriteResult.fallback(q, System.currentTimeMillis() - start);
        }

        List<MessageDetail> hist = trimHistory(historyMessages);
        boolean usedHistory = !hist.isEmpty();
        boolean followUp = complexityAnalyzer.isFollowUp(q);

        String historyHash = historyHash(hist);
        String cacheKey = ds + "\n" + q + "\n" + historyHash;
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.atMs < CACHE_TTL_MS) {
            RewriteResult hit = cached.result.copy();
            hit.setElapsedMs(System.currentTimeMillis() - start);
            hit.setFromCache(true);
            hit.setUsedHistory(usedHistory);
            hit.setFollowUp(followUp);
            return hit;
        }

        if (!deepSeekClient.isConfigured()) {
            RewriteResult fb = RewriteResult.fallback(q, System.currentTimeMillis() - start);
            fb.setUsedHistory(usedHistory);
            fb.setFollowUp(followUp);
            return fb;
        }

        try {
            String catalog = buildCatalog(ds);
            String systemPrompt = SYSTEM_PROMPT_BASE;
            if (usedHistory || followUp) {
                systemPrompt = SYSTEM_PROMPT_BASE + "\n" + SYSTEM_PROMPT_FOLLOW_UP;
            }
            String userPrompt = buildRewriteUserPrompt(q, catalog, hist, followUp);
            String raw = deepSeekClient.chatWithTimeout(systemPrompt, userPrompt, TIMEOUT_SECONDS);
            RewriteResult parsed = parse(q, raw, System.currentTimeMillis() - start);
            parsed.setUsedHistory(usedHistory);
            parsed.setFollowUp(followUp);
            if (!parsed.isFallback()) {
                cache.put(cacheKey, new CacheEntry(parsed.copy(), System.currentTimeMillis()));
            }
            return parsed;
        } catch (Exception ex) {
            log.warn("[Sidekick][rewrite] fallback: {}", ex.getMessage());
            RewriteResult fb = RewriteResult.fallback(q, System.currentTimeMillis() - start);
            fb.setUsedHistory(usedHistory);
            fb.setFollowUp(followUp);
            return fb;
        }
    }

    private static String buildRewriteUserPrompt(String question, String catalog,
                                                 List<MessageDetail> hist, boolean followUp) {
        StringBuilder sb = new StringBuilder();
        if (hist != null && !hist.isEmpty()) {
            sb.append("最近对话历史：\n");
            for (MessageDetail m : hist) {
                if (m == null || !StringUtils.hasText(m.getRole())) {
                    continue;
                }
                String role = m.getRole().trim().toLowerCase(Locale.ROOT);
                String content = clip(m.getContent(), HISTORY_CONTENT_MAX);
                if ("user".equals(role)) {
                    sb.append("用户：").append(content).append('\n');
                } else if ("assistant".equals(role)) {
                    sb.append("AI：").append(content).append('\n');
                }
            }
            sb.append("---\n\n");
        }
        sb.append("用户当前问题：").append(question).append("\n\n");
        if (followUp && (hist == null || hist.isEmpty())) {
            sb.append("（提示：当前问题疑似追问，但无可用历史，请尽量按字面改写）\n\n");
        } else if (followUp) {
            sb.append("（提示：当前问题是追问，请与上一轮用户问题合并成完整检索意图）\n\n");
        }
        sb.append("候选表清单：\n").append(catalog).append("\n\n改写：");
        return sb.toString();
    }

    /** 最近 2 轮（最多 4 条），保持时间顺序。 */
    static List<MessageDetail> trimHistory(List<MessageDetail> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return List.of();
        }
        List<MessageDetail> cleaned = new ArrayList<>();
        for (MessageDetail m : historyMessages) {
            if (m != null && StringUtils.hasText(m.getRole())) {
                cleaned.add(m);
            }
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, cleaned.size() - HISTORY_MSG_LIMIT);
        return new ArrayList<>(cleaned.subList(from, cleaned.size()));
    }

    static String historyHash(List<MessageDetail> hist) {
        if (hist == null || hist.isEmpty()) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        for (MessageDetail m : hist) {
            sb.append(m.getRole() == null ? "" : m.getRole()).append('|');
            sb.append(clip(m.getContent(), HISTORY_CONTENT_MAX)).append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 8);
        } catch (Exception ex) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static String clip(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return "";
        }
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private String buildCatalog(String datasourceId) throws Exception {
        StringBuilder sb = new StringBuilder();
        String sql = """
                SELECT table_name, table_comment, table_aliases
                FROM schema_table
                WHERE datasource_id = ?
                ORDER BY table_name
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("table_name");
                    String comment = rs.getString("table_comment");
                    String aliases = rs.getString("table_aliases");
                    sb.append(nullToEmpty(name)).append(" | ");
                    sb.append(StringUtils.hasText(comment) ? comment.trim() : "-");
                    if (StringUtils.hasText(aliases)) {
                        sb.append(" | ").append(aliases.trim());
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString().trim();
    }

    private RewriteResult parse(String original, String raw, long elapsedMs) throws Exception {
        String json = stripFence(raw);
        if (!StringUtils.hasText(json)) {
            return RewriteResult.fallback(original, elapsedMs);
        }
        JsonNode root = objectMapper.readTree(json);
        String rewritten = root.path("rewritten").asText("").trim();
        List<String> hitTables = new ArrayList<>();
        JsonNode arr = root.path("hitTables");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String t = n.asText("").trim();
                if (StringUtils.hasText(t) && !hitTables.contains(t)) {
                    hitTables.add(t);
                }
            }
        }
        if (!StringUtils.hasText(rewritten)) {
            return RewriteResult.fallback(original, elapsedMs);
        }
        if (rewritten.length() > 80) {
            rewritten = rewritten.substring(0, 80);
        }
        RewriteResult ok = new RewriteResult();
        ok.setOriginalQuery(original);
        ok.setRewrittenQuery(rewritten);
        ok.setHitTables(hitTables);
        ok.setElapsedMs(elapsedMs);
        ok.setFallback(false);
        return ok;
    }

    private static String stripFence(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence);
            }
        }
        return s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final class CacheEntry {
        private final RewriteResult result;
        private final long atMs;

        private CacheEntry(RewriteResult result, long atMs) {
            this.result = result;
            this.atMs = atMs;
        }
    }

    public static final class RewriteResult {
        private String originalQuery;
        private String rewrittenQuery;
        private List<String> hitTables = new ArrayList<>();
        private long elapsedMs;
        private boolean fallback;
        private boolean fromCache;
        private boolean usedHistory;
        private boolean followUp;

        public static RewriteResult fallback(String original, long elapsedMs) {
            RewriteResult r = new RewriteResult();
            r.originalQuery = original;
            r.rewrittenQuery = original;
            r.hitTables = new ArrayList<>();
            r.elapsedMs = elapsedMs;
            r.fallback = true;
            return r;
        }

        public RewriteResult copy() {
            RewriteResult r = new RewriteResult();
            r.originalQuery = originalQuery;
            r.rewrittenQuery = rewrittenQuery;
            r.hitTables = new ArrayList<>(hitTables);
            r.elapsedMs = elapsedMs;
            r.fallback = fallback;
            r.fromCache = fromCache;
            r.usedHistory = usedHistory;
            r.followUp = followUp;
            return r;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("originalQuery", originalQuery);
            m.put("rewrittenQuery", rewrittenQuery);
            m.put("hitTables", hitTables);
            m.put("elapsedMs", elapsedMs);
            m.put("fallback", fallback);
            m.put("fromCache", fromCache);
            m.put("usedHistory", usedHistory);
            m.put("followUp", followUp);
            return m;
        }

        public String getOriginalQuery() {
            return originalQuery;
        }

        public void setOriginalQuery(String originalQuery) {
            this.originalQuery = originalQuery;
        }

        public String getRewrittenQuery() {
            return rewrittenQuery;
        }

        public void setRewrittenQuery(String rewrittenQuery) {
            this.rewrittenQuery = rewrittenQuery;
        }

        public List<String> getHitTables() {
            return hitTables;
        }

        public void setHitTables(List<String> hitTables) {
            this.hitTables = hitTables == null ? new ArrayList<>() : hitTables;
        }

        public long getElapsedMs() {
            return elapsedMs;
        }

        public void setElapsedMs(long elapsedMs) {
            this.elapsedMs = elapsedMs;
        }

        public boolean isFallback() {
            return fallback;
        }

        public void setFallback(boolean fallback) {
            this.fallback = fallback;
        }

        public boolean isFromCache() {
            return fromCache;
        }

        public void setFromCache(boolean fromCache) {
            this.fromCache = fromCache;
        }

        public boolean isUsedHistory() {
            return usedHistory;
        }

        public void setUsedHistory(boolean usedHistory) {
            this.usedHistory = usedHistory;
        }

        public boolean isFollowUp() {
            return followUp;
        }

        public void setFollowUp(boolean followUp) {
            this.followUp = followUp;
        }
    }
}
