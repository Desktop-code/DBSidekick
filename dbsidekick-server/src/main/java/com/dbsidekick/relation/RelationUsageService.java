package com.dbsidekick.relation;

import com.dbsidekick.relation.RelationUsageRepository.InferredFkRow;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 关系用量门面：编排抽取、计分、落库和 ER 图。
 * 原方法体逐行注释在 {@code com.dbsidekick.relation.legacy}，待人工确认后清理。
 */
@Service
public class RelationUsageService {

    private static final Logger log = LoggerFactory.getLogger(RelationUsageService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final RelationUsageRepository repository;
    private final RelationScorePolicy scorePolicy;
    private final RelationGraphAssembler graphAssembler;
    private final JoinExtractor joinExtractor;
    public RelationUsageService(RelationUsageRepository repository, RelationScorePolicy scorePolicy,
                                RelationGraphAssembler graphAssembler, JoinExtractor joinExtractor) {
        this.repository = repository;
        this.scorePolicy = scorePolicy;
        this.graphAssembler = graphAssembler;
        this.joinExtractor = joinExtractor;
    }
    /** 空库时把旧推断迁入 PENDING。relation_migrated=true 时直接返回，不再跑 v2。 */
    @PostConstruct
    public void migrateLegacyInferred() {
        try {
            if (repository.settingIsTrue("relation_migrated")) {
                return;
            }
            int copied = repository.countAll() == 0 ? copyInferredRows() : 0;
            repository.writeSetting("relation_migrated", "true");
            if (copied > 0) {
                log.info("[Sidekick] migrated {} inferred relations to relation_usage", copied);
            }
        } catch (Exception ex) {
            log.warn("[Sidekick] inferred relation migration skipped: {}", ex.getMessage());
        }
        migrateV2ConfidenceRules();
    }
    /** 无强信号的 CONFIRMED 降回 PENDING；旧默认阈值 3 抬到 6。只跑一次。 */
    void migrateV2ConfidenceRules() {
        try {
            if (repository.settingIsTrue("relation_v2_migrated")) {
                return;
            }
            int demoted = demoteConfirmedWithoutStrongSignal();
            if (scorePolicy.shouldBumpLegacyThreshold(repository.readConfig("confidence-threshold"))) {
                repository.writeConfig("confidence-threshold", "6");
            }
            repository.writeSetting("relation_v2_migrated", "true");
            log.info("[Sidekick] relation v2 migration done, demoted={}", demoted);
        } catch (Exception ex) {
            log.warn("[Sidekick] relation v2 migration skipped: {}", ex.getMessage());
        }
    }
    public void recordUsage(String datasourceId, String sessionId, String questionHash, String sql, UsageContext ctx) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(sql) || ctx == null) {
            return;
        }
        List<RelationPair> pairs = joinExtractor.extract(sql);
        if (pairs.isEmpty()) {
            return;
        }
        int score = scorePolicy.scoreFor(ctx);
        if (score <= 0) {
            return;
        }
        String now = now();
        try {
            for (RelationPair pair : pairs) {
                applyUsage(datasourceId.trim(), pair, score, now, ctx, blankToNull(sessionId), blankToNull(questionHash));
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] recordUsage failed: {}", ex.getMessage());
        }
    }
    public void markConfirmed(String relationId) {
        edit(relationId, "relationId 不能为空", "确认关系失败: ", row -> {
            scorePolicy.applyManualConfirm(row, now());
            repository.update(row);
            repository.writeLog(row.getId(), null, null, scorePolicy.confirmedDelta(), "MANUAL");
            saveIfPromoted(row);
        });
    }
    /** 拒绝不写 usage_log。 */
    public void markRejected(String relationId) {
        edit(relationId, "relationId 不能为空", "拒绝关系失败: ", row -> {
            scorePolicy.applyManualReject(row, now());
            repository.update(row);
        });
    }
    /** 已存在同一列对时不改动。 */
    public boolean upsertInferred(String datasourceId, RelationPair pair) {
        if (!StringUtils.hasText(datasourceId) || pair == null) {
            return false;
        }
        try {
            if (repository.findByPair(datasourceId.trim(), pair) != null) {
                return false;
            }
            repository.insert(inferredRow(datasourceId.trim(), pair, now()));
            return true;
        } catch (Exception ex) {
            throw new IllegalStateException("写入推断关系失败: " + ex.getMessage(), ex);
        }
    }
    public List<RelationUsage> list(String datasourceId, String status, String keyword, int page, int size) {
        return list(datasourceId, null, status, keyword, page, size);
    }
    public List<RelationUsage> list(String datasourceId, String database, String status, String keyword,
                                    int page, int size) {
        if (!StringUtils.hasText(datasourceId)) {
            return List.of();
        }
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        try {
            return repository.list(datasourceId.trim(), database, status, keyword, safeSize, (safePage - 1) * safeSize);
        } catch (Exception ex) {
            throw new IllegalStateException("查询关系失败: " + ex.getMessage(), ex);
        }
    }
    public int count(String datasourceId, String status, String keyword) {
        return count(datasourceId, null, status, keyword);
    }
    /** 查询失败时返回 0。 */
    public int count(String datasourceId, String database, String status, String keyword) {
        if (!StringUtils.hasText(datasourceId)) {
            return 0;
        }
        try {
            return repository.count(datasourceId.trim(), database, status, keyword);
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] count failed: {}", ex.getMessage());
            return 0;
        }
    }
    public Map<String, Object> graph(String datasourceId, String database, String status) {
        return graphAssembler.graph(datasourceId, database, status);
    }
    public List<RelationUsage> listConfirmedTouching(String datasourceId, String tableName) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(tableName)) {
            return List.of();
        }
        try {
            return repository.listConfirmedTouching(datasourceId.trim(), tableName.trim());
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] listConfirmedTouching failed: {}", ex.getMessage());
            return List.of();
        }
    }
    /** 候选表经由 CONFIRMED 关系连到的邻接表，不含候选表自身。 */
    public Set<String> listConfirmedNeighborTables(String datasourceId, Collection<String> seedTables) {
        return graphAssembler.confirmedNeighbors(datasourceId, seedTables);
    }
    public RelationUsage get(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        try {
            return repository.findById(id.trim());
        } catch (Exception ex) {
            throw new IllegalStateException("查询关系失败: " + ex.getMessage(), ex);
        }
    }
    public void delete(String id) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id 不能为空");
        }
        try {
            repository.deleteById(id.trim());
        } catch (Exception ex) {
            throw new IllegalStateException("删除关系失败: " + ex.getMessage(), ex);
        }
    }
    public void deleteByDatasource(String datasourceId) {
        if (!StringUtils.hasText(datasourceId)) {
            return;
        }
        try {
            int deleted = repository.deleteByDatasource(datasourceId.trim());
            log.info("[Sidekick][relation] deleted {} rows for datasource={}", deleted, datasourceId);
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] deleteByDatasource failed: {}", ex.getMessage());
        }
    }
    public void resetToPending(String relationId) {
        markPending(relationId);
    }
    public void markPending(String relationId) {
        edit(relationId, "relationId 不能为空", "恢复关系失败: ", row -> {
            scorePolicy.applyReset(row, now());
            repository.update(row);
        });
    }
    static int promotionThreshold(String sources, int base, int multiplier) {
        return RelationScorePolicy.promotionThreshold(sources, base, multiplier);
    }
    public static boolean hasStrongSignal(String sources) {
        return RelationScorePolicy.hasStrongSignal(sources);
    }
    public static String displayKind(RelationUsage row) {
        return RelationScorePolicy.displayKind(row);
    }
    public List<Map<String, Object>> sourceScoreDetails(String sources) {
        return scorePolicy.sourceScoreDetails(sources);
    }
    /** 结果区点赞或点踩。按 SQL 提取 JOIN 加减分，返回受影响的关系数。 */
    public int recordFeedback(String datasourceId, String sessionId, String messageId, String sql, boolean useful) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("datasourceId 与 sql 不能为空");
        }
        List<RelationPair> pairs = joinExtractor.extract(sql);
        if (pairs.isEmpty()) {
            return 0;
        }
        int delta = scorePolicy.feedbackDelta(useful);
        String tag = RelationScorePolicy.feedbackTag(useful);
        String now = now();
        int touched = 0;
        try {
            for (RelationPair pair : pairs) {
                if (applyFeedback(datasourceId.trim(), pair, delta, tag, now,
                        blankToNull(sessionId), blankToNull(messageId))) {
                    touched++;
                }
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("写入关系反馈失败: " + ex.getMessage(), ex);
        }
        return touched;
    }
    private int demoteConfirmedWithoutStrongSignal() throws Exception {
        int demoted = 0;
        for (RelationUsage row : repository.listByStatus(RelationUsage.STATUS_CONFIRMED)) {
            if (!scorePolicy.shouldDemoteConfirmedWithoutStrongSignal(row)) {
                continue;
            }
            row.setStatus(RelationUsage.STATUS_PENDING);
            repository.update(row);
            demoted++;
        }
        return demoted;
    }
    private int copyInferredRows() throws Exception {
        int copied = 0;
        String now = now();
        for (InferredFkRow inferred : repository.selectInferredFkRows()) {
            RelationPair pair = toPair(inferred);
            if (pair == null || repository.findByPair(inferred.datasourceId(), pair) != null) {
                continue;
            }
            repository.insert(inferredRow(inferred.datasourceId(), pair, now));
            copied++;
        }
        return copied;
    }
    private void applyUsage(String datasourceId, RelationPair pair, int score, String now, UsageContext ctx,
                            String sessionId, String questionHash) throws Exception {
        RelationUsage existing = repository.findByPair(datasourceId, pair);
        if (existing != null && RelationUsage.STATUS_REJECTED.equals(existing.getStatus())) {
            return;
        }
        if (existing != null && repository.alreadyScored(existing.getId(), sessionId, questionHash)) {
            return;
        }
        RelationUsage row = existing == null ? newRow(datasourceId, pair) : existing;
        if (existing == null) {
            scorePolicy.applyNewUsage(row, score, ctx, now);
            repository.insert(row);
        } else {
            scorePolicy.applyExistingUsage(row, score, ctx, now);
            repository.update(row);
        }
        repository.writeLog(row.getId(), sessionId, questionHash, score, ctx.sourceTag());
        saveIfPromoted(row);
    }
    private boolean applyFeedback(String datasourceId, RelationPair pair, int delta, String tag, String now,
                                  String sessionId, String messageId) throws Exception {
        RelationUsage existing = repository.findByPair(datasourceId, pair);
        if (existing != null && RelationUsage.STATUS_REJECTED.equals(existing.getStatus())) {
            return false;
        }
        RelationUsage row = existing == null ? newRow(datasourceId, pair) : existing;
        if (existing == null) {
            scorePolicy.applyNewFeedback(row, delta, tag, now);
            repository.insert(row);
        } else {
            scorePolicy.applyExistingFeedback(row, delta, tag, now);
            repository.update(row);
        }
        repository.writeLog(row.getId(), sessionId, messageId, delta, tag);
        if ("DISLIKED".equals(tag)) {
            int streak = scorePolicy.leadingDislikeStreak(repository.recentLogSources(row.getId()));
            if (scorePolicy.shouldRejectForDislikeStreak(streak)) {
                scorePolicy.applyDislikeRejection(row);
                repository.update(row);
                return true;
            }
            saveIfDemoted(row);
        } else {
            saveIfPromoted(row);
        }
        return true;
    }
    private void saveIfPromoted(RelationUsage row) throws Exception {
        if (scorePolicy.markPromotedIfEligible(row)) {
            repository.update(row);
            logMove("promoted", row);
        }
    }
    private void saveIfDemoted(RelationUsage row) throws Exception {
        if (scorePolicy.markDemotedIfNeeded(row)) {
            repository.update(row);
            logMove("demoted", row);
        }
    }
    private void edit(String relationId, String emptyMessage, String failPrefix, RowWork work) {
        if (!StringUtils.hasText(relationId)) {
            throw new IllegalArgumentException(emptyMessage);
        }
        try {
            work.apply(requireRow(relationId));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(failPrefix + ex.getMessage(), ex);
        }
    }
    private RelationUsage requireRow(String relationId) throws Exception {
        RelationUsage row = repository.findById(relationId.trim());
        if (row == null) {
            throw new IllegalArgumentException("关系不存在: " + relationId);
        }
        return row;
    }
    private static RelationUsage newRow(String datasourceId, RelationPair pair) {
        RelationUsage row = new RelationUsage();
        row.setId(UUID.randomUUID().toString());
        row.setDatasourceId(datasourceId);
        row.setSourceTable(pair.sourceTable());
        row.setSourceColumn(pair.sourceColumn());
        row.setTargetTable(pair.targetTable());
        row.setTargetColumn(pair.targetColumn());
        return row;
    }
    private static RelationUsage inferredRow(String datasourceId, RelationPair pair, String now) {
        RelationUsage row = newRow(datasourceId, pair);
        row.setHitCount(0);
        row.setConfidence(0);
        row.setStatus(RelationUsage.STATUS_PENDING);
        row.setSources("INFERRED");
        row.setFirstSeenAt(now);
        return row;
    }
    private static RelationPair toPair(InferredFkRow inferred) {
        String ref = inferred.foreignKeyRef();
        int dot = ref == null ? -1 : ref.lastIndexOf('.');
        if (dot <= 0 || dot >= ref.length() - 1) {
            return null;
        }
        return RelationPair.normalized(inferred.tableName(), inferred.columnName(),
                ref.substring(0, dot).trim(), ref.substring(dot + 1).trim());
    }
    private static void logMove(String verb, RelationUsage row) {
        log.info("[Sidekick] relation {}: {}.{} → {}.{} (confidence={}, sources={})",
                verb, row.getSourceTable(), row.getSourceColumn(), row.getTargetTable(), row.getTargetColumn(),
                row.getConfidence(), row.getSources());
    }
    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
    private static String now() {
        return TS.format(Instant.now());
    }
    @FunctionalInterface
    private interface RowWork {
        void apply(RelationUsage row) throws Exception;
    }
}
