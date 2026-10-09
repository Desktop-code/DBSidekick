package com.dbsidekick.text2sql;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.config.SqliteInitializer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Few-shot 示例：写入成功 SQL + 关键词检索相似示例。
 */
@Service
public class FewShotService {

    private static final Logger log = LoggerFactory.getLogger(FewShotService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final Pattern CN_WORD = Pattern.compile("[\\u4e00-\\u9fff]{2,}|[a-zA-Z_]{2,}|\\d+");

    private final SqliteInitializer sqliteInitializer;
    private final SidekickAiProperties aiProperties;

    public FewShotService(SqliteInitializer sqliteInitializer, SidekickAiProperties aiProperties) {
        this.sqliteInitializer = sqliteInitializer;
        this.aiProperties = aiProperties;
    }

    public boolean isEnabled() {
        return cfg().isEnabled();
    }

    public List<FewShotExample> findSimilar(String datasourceId, String question,
                                            List<String> candidateTables, int topK) {
        if (!isEnabled() || !StringUtils.hasText(datasourceId) || !StringUtils.hasText(question)) {
            return List.of();
        }
        int k = topK > 0 ? topK : Math.max(1, cfg().getTopK());
        Set<String> qTokens = tokenize(question);
        if (qTokens.isEmpty()) {
            return List.of();
        }
        Set<String> cand = new HashSet<>();
        if (candidateTables != null) {
            for (String t : candidateTables) {
                if (StringUtils.hasText(t)) {
                    cand.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<FewShotExample> all = loadByDatasource(datasourceId.trim());
        List<FewShotExample> scored = new ArrayList<>();
        for (FewShotExample ex : all) {
            // 必须与当前候选表有交集，否则跳过
            if (!cand.isEmpty() && !hasTableIntersection(ex.getTablesUsed(), cand)) {
                continue;
            }
            Set<String> exTokens = tokenize(ex.getQuestion());
            if (exTokens.isEmpty()) {
                continue;
            }
            int overlap = 0;
            for (String t : qTokens) {
                if (exTokens.contains(t)) {
                    overlap++;
                }
            }
            double sim = (double) overlap / qTokens.size();
            // 表交集加分
            int tableHits = 0;
            if (!cand.isEmpty() && StringUtils.hasText(ex.getTablesUsed())) {
                for (String t : ex.getTablesUsed().split("[,，\\s]+")) {
                    if (cand.contains(t.trim().toLowerCase(Locale.ROOT))) {
                        tableHits++;
                    }
                }
            }
            if (tableHits > 0) {
                sim += 0.15 * Math.min(tableHits, 3);
            }
            if (sim <= 0) {
                continue;
            }
            ex.setScore(sim);
            scored.add(ex);
        }
        scored.sort(Comparator.comparingDouble(FewShotExample::getScore).reversed());
        if (scored.size() > k) {
            return new ArrayList<>(scored.subList(0, k));
        }
        return scored;
    }

    public void markUsed(List<FewShotExample> examples) {
        if (examples == null || examples.isEmpty()) {
            return;
        }
        String now = TS.format(Instant.now());
        String sql = "UPDATE few_shot_example SET hit_count = hit_count + 1, last_used_at = ? WHERE id = ?";
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (FewShotExample ex : examples) {
                if (ex == null || !StringUtils.hasText(ex.getId())) {
                    continue;
                }
                ps.setString(1, now);
                ps.setString(2, ex.getId());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception ex) {
            log.warn("[Sidekick][fewshot] markUsed failed: {}", ex.getMessage());
        }
    }

    /**
     * 成功执行后尝试写入示例。
     */
    public void trySave(String datasourceId, String sessionId, String question,
                        String sql, int rowCount) {
        if (!isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(question) || !StringUtils.hasText(sql)) {
            return;
        }
        int minRows = Math.max(1, cfg().getMinRowCount());
        if (rowCount < minRows) {
            return;
        }
        String q = question.trim();
        String s = sql.trim();
        if (s.length() < 20) {
            return;
        }
        String upper = s.toUpperCase(Locale.ROOT);
        if (upper.matches("(?s).*\\bSELECT\\s+1\\b.*") || upper.contains("SELECT NOW()")
                || upper.contains("SELECT CURRENT_TIMESTAMP")) {
            return;
        }
        List<String> tables = extractTables(s);
        if (tables.isEmpty()) {
            return;
        }
        if (upper.contains("SELECT *") && tables.size() > 5) {
            return;
        }

        try {
            if (StringUtils.hasText(sessionId) && existsSameQuestion(datasourceId.trim(), q)) {
                return;
            }
            insert(datasourceId.trim(), q, s, String.join(",", tables));
        } catch (Exception ex) {
            log.warn("[Sidekick][fewshot] save failed: {}", ex.getMessage());
        }
    }

    public static List<String> extractTables(String sql) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return List.of();
            }
            TablesNamesFinder finder = new TablesNamesFinder();
            List<String> found = finder.getTableList(stmt);
            if (found != null) {
                for (String n : found) {
                    if (StringUtils.hasText(n)) {
                        String clean = n.replace("`", "").replace("\"", "").trim();
                        int dot = clean.lastIndexOf('.');
                        if (dot >= 0 && dot < clean.length() - 1) {
                            clean = clean.substring(dot + 1);
                        }
                        names.add(clean);
                    }
                }
            }
        } catch (Exception ex) {
            // fallback: 简单正则
            Matcher m = Pattern.compile("(?i)\\b(?:from|join)\\s+([`\"\\w.]+)").matcher(sql);
            while (m.find()) {
                String n = m.group(1).replace("`", "").replace("\"", "");
                int dot = n.lastIndexOf('.');
                if (dot >= 0) {
                    n = n.substring(dot + 1);
                }
                if (StringUtils.hasText(n)) {
                    names.add(n);
                }
            }
        }
        return new ArrayList<>(names);
    }

    private void insert(String datasourceId, String question, String sql, String tablesUsed) throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO few_shot_example(id, datasource_id, question, sql_text, tables_used, hit_count, created_at)
                     VALUES (?, ?, ?, ?, ?, 0, ?)
                     """)) {
            ps.setString(1, id);
            ps.setString(2, datasourceId);
            ps.setString(3, question);
            ps.setString(4, sql);
            ps.setString(5, tablesUsed);
            ps.setString(6, TS.format(Instant.now()));
            ps.executeUpdate();
            log.info("[Sidekick][fewshot] saved id={} tables={}", id, tablesUsed);
        }
    }

    private boolean existsSameQuestion(String datasourceId, String question) throws Exception {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT 1 FROM few_shot_example WHERE datasource_id = ? AND question = ? LIMIT 1
                     """)) {
            ps.setString(1, datasourceId);
            ps.setString(2, question);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private List<FewShotExample> loadByDatasource(String datasourceId) {
        List<FewShotExample> list = new ArrayList<>();
        String sql = """
                SELECT id, datasource_id, question, sql_text, tables_used, hit_count, last_used_at, created_at
                FROM few_shot_example WHERE datasource_id = ?
                ORDER BY created_at DESC
                LIMIT 200
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    FewShotExample ex = new FewShotExample();
                    ex.setId(rs.getString("id"));
                    ex.setDatasourceId(rs.getString("datasource_id"));
                    ex.setQuestion(rs.getString("question"));
                    ex.setSqlText(rs.getString("sql_text"));
                    ex.setTablesUsed(rs.getString("tables_used"));
                    ex.setHitCount(rs.getInt("hit_count"));
                    ex.setLastUsedAt(rs.getString("last_used_at"));
                    ex.setCreatedAt(rs.getString("created_at"));
                    list.add(ex);
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][fewshot] load failed: {}", ex.getMessage());
        }
        return list;
    }

    private static boolean hasTableIntersection(String tablesUsed, Set<String> candidateLower) {
        if (!StringUtils.hasText(tablesUsed) || candidateLower == null || candidateLower.isEmpty()) {
            return false;
        }
        for (String t : tablesUsed.split("[,，\\s]+")) {
            String n = t.trim().toLowerCase(Locale.ROOT);
            if (StringUtils.hasText(n) && candidateLower.contains(n)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return tokens;
        }
        Matcher m = CN_WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            tokens.add(m.group());
        }
        // 中文 bigram
        String cn = text.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i + 1 < cn.length(); i++) {
            tokens.add(cn.substring(i, i + 2));
        }
        return tokens;
    }

    private SidekickAiProperties.Fewshot cfg() {
        return aiProperties.getFewshot() == null
                ? new SidekickAiProperties.Fewshot()
                : aiProperties.getFewshot();
    }
}
