package com.dbsidekick.relation;

import com.dbsidekick.config.SqliteInitializer;
import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 隐式反馈关系库：记录 JOIN 使用、晋升 CONFIRMED。
 */
@Service
public class RelationUsageService {

    private static final Logger log = LoggerFactory.getLogger(RelationUsageService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SqliteInitializer sqliteInitializer;
    private final JoinExtractor joinExtractor;
    private final RelationConfigService relationConfigService;

    public RelationUsageService(SqliteInitializer sqliteInitializer,
                                JoinExtractor joinExtractor,
                                RelationConfigService relationConfigService) {
        this.sqliteInitializer = sqliteInitializer;
        this.joinExtractor = joinExtractor;
        this.relationConfigService = relationConfigService;
    }

    /**
     * relation_usage 为空时，把旧的 schema_column.is_inferred 迁入 PENDING。
     * app_settings.relation_migrated=true 后不再执行。
     */
    @PostConstruct
    public void migrateLegacyInferred() {
        try (Connection conn = sqliteInitializer.open()) {
            if (settingIsTrue(conn, "relation_migrated")) {
                return;
            }
            int n = 0;
            if (countAll(conn) == 0) {
                n = copyInferredRows(conn);
            }
            writeSetting(conn, "relation_migrated", "true");
            if (n > 0) {
                log.info("[Sidekick] migrated {} inferred relations to relation_usage", n);
            }
        } catch (Exception ex) {
            log.warn("[Sidekick] inferred relation migration skipped: {}", ex.getMessage());
        }
    }

    public void recordUsage(String datasourceId, String sessionId, String questionHash,
                            String sql, UsageContext ctx) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(sql) || ctx == null) {
            return;
        }
        List<RelationPair> pairs = joinExtractor.extract(sql);
        if (pairs.isEmpty()) {
            return;
        }
        int score = scoreFor(ctx);
        if (score <= 0) {
            return;
        }
        String ds = datasourceId.trim();
        String sid = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
        String qh = StringUtils.hasText(questionHash) ? questionHash.trim() : null;
        String now = TS.format(Instant.now());
        try (Connection conn = sqliteInitializer.open()) {
            for (RelationPair pair : pairs) {
                upsertUsage(conn, ds, pair, score, now, ctx, sid, qh);
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] recordUsage failed: {}", ex.getMessage());
        }
    }

    public void markConfirmed(String relationId) {
        if (!StringUtils.hasText(relationId)) {
            throw new IllegalArgumentException("relationId 不能为空");
        }
        try (Connection conn = sqliteInitializer.open()) {
            RelationUsage r = getById(conn, relationId.trim());
            if (r == null) {
                throw new IllegalArgumentException("关系不存在: " + relationId);
            }
            r.setStatus(RelationUsage.STATUS_CONFIRMED);
            r.setConfidence(r.getConfidence() + relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED));
            r.setSources(appendSource(r.getSources(), "MANUAL"));
            r.setLastUsedAt(TS.format(Instant.now()));
            updateRow(conn, r);
            writeLog(conn, r.getId(), null, null,
                    relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED), "MANUAL");
            promoteIfNeeded(conn, r);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("确认关系失败: " + ex.getMessage(), ex);
        }
    }

    public void markRejected(String relationId) {
        if (!StringUtils.hasText(relationId)) {
            throw new IllegalArgumentException("relationId 不能为空");
        }
        try (Connection conn = sqliteInitializer.open()) {
            RelationUsage r = getById(conn, relationId.trim());
            if (r == null) {
                throw new IllegalArgumentException("关系不存在: " + relationId);
            }
            r.setStatus(RelationUsage.STATUS_REJECTED);
            r.setConfidence(relationConfigService.getInt(RelationConfigService.KEY_SCORE_REJECTED));
            r.setSources(appendSource(r.getSources(), "MANUAL"));
            r.setLastUsedAt(TS.format(Instant.now()));
            updateRow(conn, r);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("拒绝关系失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * @return true 表示新插入；false 表示已存在未改动
     */
    public boolean upsertInferred(String datasourceId, RelationPair pair) {
        if (!StringUtils.hasText(datasourceId) || pair == null) {
            return false;
        }
        String ds = datasourceId.trim();
        try (Connection conn = sqliteInitializer.open()) {
            RelationUsage existing = findByPair(conn, ds, pair);
            if (existing != null) {
                return false;
            }
            RelationUsage r = new RelationUsage();
            r.setId(UUID.randomUUID().toString());
            r.setDatasourceId(ds);
            r.setSourceTable(pair.sourceTable());
            r.setSourceColumn(pair.sourceColumn());
            r.setTargetTable(pair.targetTable());
            r.setTargetColumn(pair.targetColumn());
            r.setHitCount(0);
            r.setConfidence(0);
            r.setStatus(RelationUsage.STATUS_PENDING);
            r.setSources("INFERRED");
            r.setFirstSeenAt(TS.format(Instant.now()));
            insertRow(conn, r);
            return true;
        } catch (Exception ex) {
            throw new IllegalStateException("写入推断关系失败: " + ex.getMessage(), ex);
        }
    }

    public List<RelationUsage> list(String datasourceId, String status, String keyword, int page, int size) {
        if (!StringUtils.hasText(datasourceId)) {
            return List.of();
        }
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 200);
        int offset = (p - 1) * sz;
        String ds = datasourceId.trim();
        boolean filterStatus = StringUtils.hasText(status);
        boolean filterKeyword = StringUtils.hasText(keyword);
        StringBuilder sql = new StringBuilder("""
                SELECT id, datasource_id, source_table, source_column, target_table, target_column,
                       hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
                FROM relation_usage
                WHERE datasource_id = ?
                """);
        if (filterStatus) {
            sql.append(" AND status = ?");
        }
        if (filterKeyword) {
            sql.append("""
                     AND (source_table LIKE ? OR source_column LIKE ?
                       OR target_table LIKE ? OR target_column LIKE ?)
                    """);
        }
        sql.append("""
                 ORDER BY confidence DESC,
                          CASE WHEN last_used_at IS NULL THEN 1 ELSE 0 END,
                          last_used_at DESC, source_table, source_column
                 LIMIT ? OFFSET ?
                """);
        List<RelationUsage> list = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, ds);
            if (filterStatus) {
                ps.setString(i++, status.trim().toUpperCase(Locale.ROOT));
            }
            if (filterKeyword) {
                String like = "%" + keyword.trim() + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, like);
            }
            ps.setInt(i++, sz);
            ps.setInt(i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("查询关系失败: " + ex.getMessage(), ex);
        }
        return list;
    }

    public int count(String datasourceId, String status, String keyword) {
        if (!StringUtils.hasText(datasourceId)) {
            return 0;
        }
        String ds = datasourceId.trim();
        boolean filterStatus = StringUtils.hasText(status);
        boolean filterKeyword = StringUtils.hasText(keyword);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM relation_usage WHERE datasource_id = ?");
        if (filterStatus) {
            sql.append(" AND status = ?");
        }
        if (filterKeyword) {
            sql.append("""
                     AND (source_table LIKE ? OR source_column LIKE ?
                       OR target_table LIKE ? OR target_column LIKE ?)
                    """);
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, ds);
            if (filterStatus) {
                ps.setString(i++, status.trim().toUpperCase(Locale.ROOT));
            }
            if (filterKeyword) {
                String like = "%" + keyword.trim() + "%";
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i++, like);
                ps.setString(i, like);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] count failed: {}", ex.getMessage());
        }
        return 0;
    }

    /**
     * 与指定表相关的已确认关系（归一化后源/目标都算），按置信度降序。
     */
    public List<RelationUsage> listConfirmedTouching(String datasourceId, String tableName) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(tableName)) {
            return List.of();
        }
        String sql = """
                SELECT id, datasource_id, source_table, source_column, target_table, target_column,
                       hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
                FROM relation_usage
                WHERE datasource_id = ?
                  AND status = 'CONFIRMED'
                  AND (LOWER(source_table) = LOWER(?) OR LOWER(target_table) = LOWER(?))
                ORDER BY confidence DESC, source_table, source_column
                """;
        List<RelationUsage> list = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId.trim());
            ps.setString(2, tableName.trim());
            ps.setString(3, tableName.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] listConfirmedTouching failed: {}", ex.getMessage());
        }
        return list;
    }

    /**
     * 候选表经由 CONFIRMED 关系连到的邻接表（不含候选表自身）。
     */
    public Set<String> listConfirmedNeighborTables(String datasourceId, Collection<String> seedTables) {
        Set<String> out = new LinkedHashSet<>();
        if (!StringUtils.hasText(datasourceId) || seedTables == null || seedTables.isEmpty()) {
            return out;
        }
        Set<String> seeds = new LinkedHashSet<>();
        for (String t : seedTables) {
            if (StringUtils.hasText(t)) {
                seeds.add(t.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (seeds.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT source_table, target_table
                FROM relation_usage
                WHERE datasource_id = ? AND status = 'CONFIRMED'
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String source = rs.getString("source_table");
                    String target = rs.getString("target_table");
                    boolean sourceHit = source != null && seeds.contains(source.toLowerCase(Locale.ROOT));
                    boolean targetHit = target != null && seeds.contains(target.toLowerCase(Locale.ROOT));
                    if (sourceHit && target != null && !seeds.contains(target.toLowerCase(Locale.ROOT))) {
                        out.add(target);
                    }
                    if (targetHit && source != null && !seeds.contains(source.toLowerCase(Locale.ROOT))) {
                        out.add(source);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] neighbor lookup failed: {}", ex.getMessage());
        }
        return out;
    }

    public RelationUsage get(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        try (Connection conn = sqliteInitializer.open()) {
            return getById(conn, id.trim());
        } catch (Exception ex) {
            throw new IllegalStateException("查询关系失败: " + ex.getMessage(), ex);
        }
    }

    public void delete(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id 不能为空");
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM relation_usage WHERE id = ?")) {
            ps.setString(1, id.trim());
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException("删除关系失败: " + ex.getMessage(), ex);
        }
    }

    public void deleteByDatasource(String datasourceId) {
        if (!StringUtils.hasText(datasourceId)) {
            return;
        }
        try (Connection conn = sqliteInitializer.open()) {
            try (PreparedStatement logPs = conn.prepareStatement(
                    "DELETE FROM relation_usage_log WHERE relation_id IN (SELECT id FROM relation_usage WHERE datasource_id = ?)")) {
                logPs.setString(1, datasourceId.trim());
                logPs.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM relation_usage WHERE datasource_id = ?")) {
                ps.setString(1, datasourceId.trim());
                int n = ps.executeUpdate();
                log.info("[Sidekick][relation] deleted {} rows for datasource={}", n, datasourceId);
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] deleteByDatasource failed: {}", ex.getMessage());
        }
    }

    public void resetToPending(String relationId) {
        markPending(relationId);
    }

    public void markPending(String relationId) {
        if (!StringUtils.hasText(relationId)) {
            throw new IllegalArgumentException("relationId 不能为空");
        }
        try (Connection conn = sqliteInitializer.open()) {
            RelationUsage r = getById(conn, relationId.trim());
            if (r == null) {
                throw new IllegalArgumentException("关系不存在: " + relationId);
            }
            r.setStatus(RelationUsage.STATUS_PENDING);
            r.setConfidence(0);
            r.setSources(appendSource(r.getSources(), "RESET"));
            r.setLastUsedAt(TS.format(Instant.now()));
            updateRow(conn, r);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("恢复关系失败: " + ex.getMessage(), ex);
        }
    }

    private void upsertUsage(Connection conn, String ds, RelationPair pair, int score, String now,
                             UsageContext ctx, String sessionId, String questionHash) throws Exception {
        RelationUsage existing = findByPair(conn, ds, pair);
        if (existing != null && RelationUsage.STATUS_REJECTED.equals(existing.getStatus())) {
            return;
        }
        if (existing != null && alreadyScored(conn, existing.getId(), sessionId, questionHash)) {
            return;
        }
        RelationUsage row;
        if (existing == null) {
            row = new RelationUsage();
            row.setId(UUID.randomUUID().toString());
            row.setDatasourceId(ds);
            row.setSourceTable(pair.sourceTable());
            row.setSourceColumn(pair.sourceColumn());
            row.setTargetTable(pair.targetTable());
            row.setTargetColumn(pair.targetColumn());
            row.setHitCount(1);
            row.setConfidence(score);
            row.setStatus(RelationUsage.STATUS_PENDING);
            row.setSources(ctx.sourceTag());
            row.setFirstSeenAt(now);
            row.setLastUsedAt(now);
            row.setLastUseRows(ctx.rowCount());
            insertRow(conn, row);
        } else {
            row = existing;
            row.setHitCount(row.getHitCount() + 1);
            row.setConfidence(row.getConfidence() + score);
            row.setSources(appendSource(row.getSources(), ctx.sourceTag()));
            row.setLastUsedAt(now);
            row.setLastUseRows(ctx.rowCount());
            updateRow(conn, row);
        }
        writeLog(conn, row.getId(), sessionId, questionHash, score, ctx.sourceTag());
        promoteIfNeeded(conn, row);
    }

    private boolean alreadyScored(Connection conn, String relationId, String sessionId, String questionHash)
            throws Exception {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(questionHash)) {
            return false;
        }
        String sql = """
                SELECT 1 FROM relation_usage_log
                WHERE relation_id = ? AND session_id = ? AND question_hash = ?
                LIMIT 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, relationId);
            ps.setString(2, sessionId);
            ps.setString(3, questionHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void writeLog(Connection conn, String relationId, String sessionId, String questionHash,
                          int delta, String source) throws Exception {
        String sql = """
                INSERT INTO relation_usage_log(id, relation_id, session_id, question_hash, delta, source, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, relationId);
            ps.setString(3, sessionId);
            ps.setString(4, questionHash);
            ps.setInt(5, delta);
            ps.setString(6, source);
            ps.setString(7, TS.format(Instant.now()));
            ps.executeUpdate();
        }
    }

    /**
     * INFERRED 且没有 MANUAL 时，晋升阈值乘以 inferred-threshold-multiplier（默认 2）。
     */
    static int promotionThreshold(String sources, int base, int multiplier) {
        int threshold = base <= 0 ? 3 : base;
        int mul = multiplier <= 0 ? 2 : multiplier;
        if (hasToken(sources, "INFERRED") && !hasToken(sources, "MANUAL")) {
            return threshold * mul;
        }
        return threshold;
    }

    private void promoteIfNeeded(Connection conn, RelationUsage r) throws Exception {
        if (r == null || RelationUsage.STATUS_REJECTED.equals(r.getStatus())) {
            return;
        }
        if (!RelationUsage.STATUS_PENDING.equals(r.getStatus())) {
            return;
        }
        int threshold = promotionThreshold(
                r.getSources(),
                relationConfigService.confidenceThreshold(),
                relationConfigService.getInt(RelationConfigService.KEY_INFERRED_MULTIPLIER, 2));
        if (r.getConfidence() >= threshold) {
            r.setStatus(RelationUsage.STATUS_CONFIRMED);
            updateRow(conn, r);
            log.info("[Sidekick] relation promoted: {}.{} → {}.{} (confidence={}, sources={})",
                    r.getSourceTable(), r.getSourceColumn(),
                    r.getTargetTable(), r.getTargetColumn(), r.getConfidence(), r.getSources());
        }
    }

    private int scoreFor(UsageContext ctx) {
        int s = 0;
        if (ctx.sqlSuccess()) {
            s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_SQL_SUCCESS);
        }
        if (ctx.rowCount() > 0) {
            s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_ROW_COUNT);
        }
        if (ctx.savedToScript()) {
            s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_SAVED);
        }
        if (ctx.userConfirmed()) {
            s += relationConfigService.getInt(RelationConfigService.KEY_SCORE_CONFIRMED);
        }
        return s;
    }

    private int countAll(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM relation_usage");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int copyInferredRows(Connection conn) throws Exception {
        String q = """
                SELECT t.datasource_id, t.table_name, c.column_name, c.foreign_key_ref
                FROM schema_column c
                JOIN schema_table t ON t.id = c.table_id
                WHERE IFNULL(c.is_inferred, 0) = 1
                  AND c.foreign_key_ref IS NOT NULL
                  AND TRIM(c.foreign_key_ref) <> ''
                """;
        int n = 0;
        String now = TS.format(Instant.now());
        try (PreparedStatement ps = conn.prepareStatement(q);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String ref = rs.getString("foreign_key_ref");
                int dot = ref == null ? -1 : ref.lastIndexOf('.');
                if (dot <= 0 || dot >= ref.length() - 1) {
                    continue;
                }
                RelationPair pair = RelationPair.normalized(
                        rs.getString("table_name"),
                        rs.getString("column_name"),
                        ref.substring(0, dot).trim(),
                        ref.substring(dot + 1).trim());
                if (findByPair(conn, rs.getString("datasource_id"), pair) != null) {
                    continue;
                }
                RelationUsage r = new RelationUsage();
                r.setId(UUID.randomUUID().toString());
                r.setDatasourceId(rs.getString("datasource_id"));
                r.setSourceTable(pair.sourceTable());
                r.setSourceColumn(pair.sourceColumn());
                r.setTargetTable(pair.targetTable());
                r.setTargetColumn(pair.targetColumn());
                r.setHitCount(0);
                r.setConfidence(0);
                r.setStatus(RelationUsage.STATUS_PENDING);
                r.setSources("INFERRED");
                r.setFirstSeenAt(now);
                insertRow(conn, r);
                n++;
            }
        }
        return n;
    }

    private boolean settingIsTrue(Connection conn, String key) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "true".equalsIgnoreCase(rs.getString("value"));
            }
        }
    }

    private void writeSetting(Connection conn, String key, String value) throws Exception {
        String now = TS.format(Instant.now());
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO app_settings(key, value, updated_at) VALUES(?, ?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private static boolean hasToken(String sources, String token) {
        if (!StringUtils.hasText(sources) || !StringUtils.hasText(token)) {
            return false;
        }
        String want = token.trim().toUpperCase(Locale.ROOT);
        for (String p : sources.split(",")) {
            if (want.equals(p.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String appendSource(String sources, String add) {
        Set<String> set = new LinkedHashSet<>();
        if (StringUtils.hasText(sources)) {
            for (String p : sources.split(",")) {
                if (StringUtils.hasText(p)) {
                    set.add(p.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (StringUtils.hasText(add)) {
            set.add(add.trim().toUpperCase(Locale.ROOT));
        }
        return String.join(",", set);
    }

    private RelationUsage findByPair(Connection conn, String ds, RelationPair pair) throws Exception {
        String sql = """
                SELECT id, datasource_id, source_table, source_column, target_table, target_column,
                       hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
                FROM relation_usage
                WHERE datasource_id = ?
                  AND source_table = ? AND source_column = ?
                  AND target_table = ? AND target_column = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ds);
            ps.setString(2, pair.sourceTable());
            ps.setString(3, pair.sourceColumn());
            ps.setString(4, pair.targetTable());
            ps.setString(5, pair.targetColumn());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    private RelationUsage getById(Connection conn, String id) throws Exception {
        String sql = """
                SELECT id, datasource_id, source_table, source_column, target_table, target_column,
                       hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows
                FROM relation_usage WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        }
        return null;
    }

    private void insertRow(Connection conn, RelationUsage r) throws Exception {
        String sql = """
                INSERT INTO relation_usage(
                  id, datasource_id, source_table, source_column, target_table, target_column,
                  hit_count, confidence, status, sources, first_seen_at, last_used_at, last_use_rows)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId());
            ps.setString(2, r.getDatasourceId());
            ps.setString(3, r.getSourceTable());
            ps.setString(4, r.getSourceColumn());
            ps.setString(5, r.getTargetTable());
            ps.setString(6, r.getTargetColumn());
            ps.setInt(7, r.getHitCount());
            ps.setInt(8, r.getConfidence());
            ps.setString(9, r.getStatus());
            ps.setString(10, r.getSources());
            ps.setString(11, r.getFirstSeenAt());
            ps.setString(12, r.getLastUsedAt());
            if (r.getLastUseRows() == null) {
                ps.setNull(13, java.sql.Types.INTEGER);
            } else {
                ps.setInt(13, r.getLastUseRows());
            }
            ps.executeUpdate();
        }
    }

    private void updateRow(Connection conn, RelationUsage r) throws Exception {
        String sql = """
                UPDATE relation_usage
                SET hit_count = ?, confidence = ?, status = ?, sources = ?, last_used_at = ?, last_use_rows = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, r.getHitCount());
            ps.setInt(2, r.getConfidence());
            ps.setString(3, r.getStatus());
            ps.setString(4, r.getSources());
            ps.setString(5, r.getLastUsedAt());
            if (r.getLastUseRows() == null) {
                ps.setNull(6, java.sql.Types.INTEGER);
            } else {
                ps.setInt(6, r.getLastUseRows());
            }
            ps.setString(7, r.getId());
            ps.executeUpdate();
        }
    }

    private static RelationUsage mapRow(ResultSet rs) throws Exception {
        RelationUsage r = new RelationUsage();
        r.setId(rs.getString("id"));
        r.setDatasourceId(rs.getString("datasource_id"));
        r.setSourceTable(rs.getString("source_table"));
        r.setSourceColumn(rs.getString("source_column"));
        r.setTargetTable(rs.getString("target_table"));
        r.setTargetColumn(rs.getString("target_column"));
        r.setHitCount(rs.getInt("hit_count"));
        r.setConfidence(rs.getInt("confidence"));
        r.setStatus(rs.getString("status"));
        r.setSources(rs.getString("sources"));
        r.setFirstSeenAt(rs.getString("first_seen_at"));
        r.setLastUsedAt(rs.getString("last_used_at"));
        int rows = rs.getInt("last_use_rows");
        r.setLastUseRows(rs.wasNull() ? null : rows);
        return r;
    }
}
