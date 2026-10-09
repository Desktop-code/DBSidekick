package com.dbsidekick.relation;

import com.dbsidekick.config.SidekickProperties;
import com.dbsidekick.config.SqliteInitializer;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 关系学习参数：SQLite relation_config → application.yml → 代码兜底。
 */
@Service
public class RelationConfigService {

    private static final Logger log = LoggerFactory.getLogger(RelationConfigService.class);

    public static final String KEY_THRESHOLD = "confidence-threshold";
    public static final String KEY_SCORE_SQL_SUCCESS = "score.sql-success";
    public static final String KEY_SCORE_ROW_COUNT = "score.row-count-positive";
    public static final String KEY_SCORE_SAVED = "score.saved-to-script";
    public static final String KEY_SCORE_CONFIRMED = "score.user-confirmed";
    public static final String KEY_SCORE_REJECTED = "score.user-rejected";
    public static final String KEY_EXCLUDED_COLUMNS = "excluded-column-names";
    public static final String KEY_TEMP_PREFIXES = "temp-table-prefixes";
    public static final String KEY_ADJACENCY_LIMIT = "adjacency-whitelist-limit";
    public static final String KEY_INFERRED_MULTIPLIER = "inferred-threshold-multiplier";

    private static final List<String> KEYS = List.of(
            KEY_THRESHOLD,
            KEY_SCORE_SQL_SUCCESS,
            KEY_SCORE_ROW_COUNT,
            KEY_SCORE_SAVED,
            KEY_SCORE_CONFIRMED,
            KEY_SCORE_REJECTED,
            KEY_EXCLUDED_COLUMNS,
            KEY_TEMP_PREFIXES,
            KEY_ADJACENCY_LIMIT,
            KEY_INFERRED_MULTIPLIER);

    /** 代码兜底，仅当 SQLite 与 yml 都没有该键时使用。 */
    private static final Map<String, String> CODE_DEFAULTS = new LinkedHashMap<>();

    static {
        CODE_DEFAULTS.put(KEY_THRESHOLD, "3");
        CODE_DEFAULTS.put(KEY_SCORE_SQL_SUCCESS, "1");
        CODE_DEFAULTS.put(KEY_SCORE_ROW_COUNT, "1");
        CODE_DEFAULTS.put(KEY_SCORE_SAVED, "5");
        CODE_DEFAULTS.put(KEY_SCORE_CONFIRMED, "10");
        CODE_DEFAULTS.put(KEY_SCORE_REJECTED, "-100");
        CODE_DEFAULTS.put(KEY_EXCLUDED_COLUMNS, "id,uuid,guid,code,no,num,sort,order,seq,version");
        CODE_DEFAULTS.put(KEY_TEMP_PREFIXES, "tmp_,temp_,_tmp_,_temp_");
        CODE_DEFAULTS.put(KEY_ADJACENCY_LIMIT, "8");
        CODE_DEFAULTS.put(KEY_INFERRED_MULTIPLIER, "2");
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SqliteInitializer sqliteInitializer;
    private final SidekickProperties sidekickProperties;

    public RelationConfigService(SqliteInitializer sqliteInitializer, SidekickProperties sidekickProperties) {
        this.sqliteInitializer = sqliteInitializer;
        this.sidekickProperties = sidekickProperties;
    }

    @PostConstruct
    public void seedMissing() {
        try {
            int inserted = 0;
            for (String key : KEYS) {
                if (loadSqlite(key) == null) {
                    write(key, resolveWithoutSqlite(key));
                    inserted++;
                }
            }
            if (inserted > 0) {
                log.info("[Sidekick] relation_config seeded {} keys", inserted);
            }
        } catch (Exception ex) {
            log.warn("[Sidekick] relation_config seed failed: {}", ex.getMessage());
        }
    }

    public String get(String key) {
        if (!KEYS.contains(key)) {
            throw new IllegalArgumentException("未知配置项: " + key);
        }
        String db = loadSqliteQuiet(key);
        if (db != null) {
            return db;
        }
        return resolveWithoutSqlite(key);
    }

    public int getInt(String key) {
        return getInt(key, Integer.parseInt(CODE_DEFAULTS.getOrDefault(key, "0")));
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    public String getString(String key, String defaultValue) {
        try {
            String value = get(key);
            return value == null ? defaultValue : value;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    public List<String> getList(String key) {
        String raw = getString(key, "");
        List<String> list = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (StringUtils.hasText(part)) {
                list.add(part.trim());
            }
        }
        return list;
    }

    public void set(String key, String value) {
        update(Map.of(key, value == null ? "" : value));
    }

    public Map<String, String> getAll() {
        return all();
    }

    public int confidenceThreshold() {
        int t = getInt(KEY_THRESHOLD);
        return t <= 0 ? Integer.parseInt(CODE_DEFAULTS.get(KEY_THRESHOLD)) : t;
    }

    public Set<String> excludedColumnNames() {
        Set<String> set = new LinkedHashSet<>();
        for (String part : get(KEY_EXCLUDED_COLUMNS).split(",")) {
            if (StringUtils.hasText(part)) {
                set.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return set;
    }

    public List<String> tempTablePrefixes() {
        List<String> list = new ArrayList<>();
        for (String part : get(KEY_TEMP_PREFIXES).split(",")) {
            if (StringUtils.hasText(part)) {
                list.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        return list;
    }

    public Map<String, String> all() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : KEYS) {
            map.put(key, get(key));
        }
        return map;
    }

    public void update(Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : body.entrySet()) {
            String key = e.getKey();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("未知配置项: " + key);
            }
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (isIntKey(key)) {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException(key + " 必须是整数");
                }
            }
            try {
                write(key, value);
            } catch (Exception ex) {
                throw new IllegalStateException("保存关系配置失败: " + ex.getMessage(), ex);
            }
        }
    }

    private static boolean isIntKey(String key) {
        return KEY_THRESHOLD.equals(key)
                || key.startsWith("score.");
    }

    private String resolveWithoutSqlite(String key) {
        String yml = fromYml(key);
        if (yml != null) {
            return yml;
        }
        return CODE_DEFAULTS.get(key);
    }

    private String fromYml(String key) {
        SidekickProperties.Relation rel = sidekickProperties == null ? null : sidekickProperties.getRelation();
        if (rel == null) {
            return null;
        }
        SidekickProperties.Score score = rel.getScore();
        return switch (key) {
            case KEY_THRESHOLD -> rel.getConfidenceThreshold() == null
                    ? null : String.valueOf(rel.getConfidenceThreshold());
            case KEY_EXCLUDED_COLUMNS -> blankToNull(rel.getExcludedColumnNames());
            case KEY_TEMP_PREFIXES -> blankToNull(rel.getTempTablePrefixes());
            case KEY_ADJACENCY_LIMIT -> rel.getAdjacencyWhitelistLimit() == null
                    ? null : String.valueOf(rel.getAdjacencyWhitelistLimit());
            case KEY_INFERRED_MULTIPLIER -> rel.getInferredThresholdMultiplier() == null
                    ? null : String.valueOf(rel.getInferredThresholdMultiplier());
            case KEY_SCORE_SQL_SUCCESS -> score == null || score.getSqlSuccess() == null
                    ? null : String.valueOf(score.getSqlSuccess());
            case KEY_SCORE_ROW_COUNT -> score == null || score.getRowCountPositive() == null
                    ? null : String.valueOf(score.getRowCountPositive());
            case KEY_SCORE_SAVED -> score == null || score.getSavedToScript() == null
                    ? null : String.valueOf(score.getSavedToScript());
            case KEY_SCORE_CONFIRMED -> score == null || score.getUserConfirmed() == null
                    ? null : String.valueOf(score.getUserConfirmed());
            case KEY_SCORE_REJECTED -> score == null || score.getUserRejected() == null
                    ? null : String.valueOf(score.getUserRejected());
            default -> null;
        };
    }

    private String loadSqliteQuiet(String key) {
        try {
            return loadSqlite(key);
        } catch (Exception ex) {
            log.warn("[Sidekick] relation_config read {} failed: {}", key, ex.getMessage());
            return null;
        }
    }

    private String loadSqlite(String key) throws Exception {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT value FROM relation_config WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        }
        return null;
    }

    private void write(String key, String value) throws Exception {
        String now = TS.format(Instant.now());
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO relation_config(key, value, updated_at) VALUES(?, ?, ?)
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                     """)) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
