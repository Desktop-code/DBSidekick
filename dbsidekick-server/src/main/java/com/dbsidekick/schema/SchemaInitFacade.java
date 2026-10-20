package com.dbsidekick.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 一键初始化编排：按库执行 SYNC → (INFER ∥ ALIAS) → INDEX。
 * <p>
 * 不替代单步 API；仅编排已有 Service。
 */
@Service
public class SchemaInitFacade {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitFacade.class);

    private final SchemaSyncService schemaSyncService;
    private final RelationInferService relationInferService;
    private final TableAliasGenerator tableAliasGenerator;
    private final SchemaVectorService schemaVectorService;
    private final ExecutorService parallelExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "schema-init-parallel");
        t.setDaemon(true);
        return t;
    });

    public SchemaInitFacade(SchemaSyncService schemaSyncService,
                            RelationInferService relationInferService,
                            TableAliasGenerator tableAliasGenerator,
                            SchemaVectorService schemaVectorService) {
        this.schemaSyncService = schemaSyncService;
        this.relationInferService = relationInferService;
        this.tableAliasGenerator = tableAliasGenerator;
        this.schemaVectorService = schemaVectorService;
    }

    /**
     * 执行一键初始化并通过 sink 推送 SSE 事件。
     *
     * @param datasourceId     数据源 ID
     * @param database         指定库；为空则按库顺序对实例内每个库跑完整 DAG
     * @param overwriteAliases 是否覆盖已有别名
     * @param sink             事件消费者；不可为 null
     * @param cancelled        客户端断开时置 true，尽快停止
     */
    public void run(String datasourceId, String database, boolean overwriteAliases,
                    Consumer<SchemaInitSseEvent> sink, AtomicBoolean cancelled) {
        if (sink == null) {
            throw new IllegalArgumentException("sink 不能为空");
        }
        if (!StringUtils.hasText(datasourceId)) {
            sink.accept(SchemaInitSseEvent.error("datasourceId 不能为空"));
            sink.accept(SchemaInitSseEvent.done());
            return;
        }
        AtomicBoolean stop = cancelled == null ? new AtomicBoolean(false) : cancelled;
        String ds = datasourceId.trim();
        long start = System.currentTimeMillis();
        String taskId = UUID.randomUUID().toString();

        try {
            List<String> databases = resolveDatabases(ds, database);
            if (databases.isEmpty()) {
                sink.accept(SchemaInitSseEvent.error("没有可初始化的数据库"));
                sink.accept(SchemaInitSseEvent.done());
                return;
            }

            List<String> stepNames = List.of(
                    SchemaInitStep.SYNC.name(),
                    SchemaInitStep.INFER.name(),
                    SchemaInitStep.ALIAS.name(),
                    SchemaInitStep.INDEX.name());
            sink.accept(SchemaInitSseEvent.initStart(taskId, ds,
                    StringUtils.hasText(database) ? database.trim() : null,
                    databases, stepNames));

            boolean allOk = true;
            List<Map<String, Object>> dbSummaries = new ArrayList<>();
            int dbIndex = 0;
            for (String db : databases) {
                if (stop.get()) {
                    allOk = false;
                    break;
                }
                dbIndex++;
                sink.accept(SchemaInitSseEvent.dbStart(db, dbIndex, databases.size()));
                DbRunResult result = runOneDatabase(ds, db, overwriteAliases, sink, stop);
                dbSummaries.add(result.toMap());
                sink.accept(SchemaInitSseEvent.dbDone(db, result.success(), result.message()));
                if (!result.success()) {
                    allOk = false;
                }
            }

            String overall = allOk ? "SUCCEEDED" : "FAILED";
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("databases", dbSummaries);
            sink.accept(SchemaInitSseEvent.initDone(overall, System.currentTimeMillis() - start, summary));
        } catch (Exception ex) {
            log.warn("[Sidekick][init] failed datasource={}: {}", ds, ex.getMessage());
            sink.accept(SchemaInitSseEvent.error(friendly(ex)));
            sink.accept(SchemaInitSseEvent.initDone("FAILED", System.currentTimeMillis() - start, null));
        } finally {
            sink.accept(SchemaInitSseEvent.done());
        }
    }

    private List<String> resolveDatabases(String datasourceId, String database) throws Exception {
        if (StringUtils.hasText(database)) {
            return List.of(database.trim());
        }
        return schemaSyncService.listDatabases(datasourceId);
    }

    private DbRunResult runOneDatabase(String datasourceId, String database, boolean overwriteAliases,
                                       Consumer<SchemaInitSseEvent> sink, AtomicBoolean stop) {
        // --- SYNC ---
        if (stop.get()) {
            return DbRunResult.fail(database, "已取消");
        }
        emitStep(sink, database, SchemaInitStep.SYNC, "RUNNING", "正在同步表结构…", null, null, null, null);
        SchemaSyncService.SyncResult syncResult;
        try {
            ProgressListener syncProgress = (step, current, total, message) ->
                    sink.accept(SchemaInitSseEvent.progress(database, SchemaInitStep.SYNC, current, total, message));
            syncResult = schemaSyncService.sync(datasourceId, database,
                    SyncOptions.of(true, syncProgress));
            emitStep(sink, database, SchemaInitStep.SYNC, "SUCCEEDED",
                    "已同步 " + syncResult.tableCount() + " 张表",
                    syncResult.tableCount(), syncResult.tableCount(), syncResult.toMap(), null);
        } catch (Exception ex) {
            String msg = friendly(ex);
            emitStep(sink, database, SchemaInitStep.SYNC, "FAILED", msg, null, null, null, msg);
            markSkipped(sink, database, SchemaInitStep.INFER, SchemaInitStep.ALIAS, SchemaInitStep.INDEX);
            return DbRunResult.fail(database, "同步失败: " + msg);
        }

        if (stop.get()) {
            return DbRunResult.fail(database, "已取消");
        }

        // --- INFER ∥ ALIAS ---
        emitStep(sink, database, SchemaInitStep.INFER, "RUNNING", "正在推断表关系…", null, null, null, null);
        emitStep(sink, database, SchemaInitStep.ALIAS, "RUNNING", "正在生成表别名…", null, null, null, null);

        CompletableFuture<StepOutcome> inferFuture = CompletableFuture.supplyAsync(() -> {
            try {
                ProgressListener p = (step, current, total, message) ->
                        sink.accept(SchemaInitSseEvent.progress(database, SchemaInitStep.INFER, current, total, message));
                InferReport report = relationInferService.infer(datasourceId, database, p);
                String message = "推断 " + report.inferredCount() + " 条关系（已有 "
                        + report.existingCount() + " 条）";
                return StepOutcome.ok(report.toMap(), message);
            } catch (Exception ex) {
                return StepOutcome.fail(friendly(ex));
            }
        }, parallelExecutor);

        CompletableFuture<StepOutcome> aliasFuture = CompletableFuture.supplyAsync(() -> {
            try {
                ProgressListener p = (step, current, total, message) ->
                        sink.accept(SchemaInitSseEvent.progress(database, SchemaInitStep.ALIAS, current, total, message));
                TableAliasGenerator.GenReport report =
                        tableAliasGenerator.generateAll(datasourceId, overwriteAliases, database, p);
                // 部分失败仍标 SUCCEEDED，message 带明细
                String message = "成功 " + report.getGenerated() + " 张，跳过 " + report.getSkipped()
                        + " 张" + (report.getFailed() > 0 ? "，失败 " + report.getFailed() + " 张" : "");
                return StepOutcome.ok(report.toMap(), message);
            } catch (Exception ex) {
                return StepOutcome.fail(friendly(ex));
            }
        }, parallelExecutor);

        StepOutcome inferOutcome;
        StepOutcome aliasOutcome;
        try {
            CompletableFuture.allOf(inferFuture, aliasFuture).join();
            inferOutcome = inferFuture.join();
            aliasOutcome = aliasFuture.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String msg = friendly(cause);
            emitStep(sink, database, SchemaInitStep.INFER, "FAILED", msg, null, null, null, msg);
            emitStep(sink, database, SchemaInitStep.ALIAS, "FAILED", msg, null, null, null, msg);
            markSkipped(sink, database, SchemaInitStep.INDEX);
            return DbRunResult.fail(database, msg);
        }

        if (inferOutcome.success()) {
            emitStep(sink, database, SchemaInitStep.INFER, "SUCCEEDED", inferOutcome.message(),
                    null, null, inferOutcome.result(), null);
        } else {
            emitStep(sink, database, SchemaInitStep.INFER, "FAILED", inferOutcome.message(),
                    null, null, null, inferOutcome.message());
        }
        if (aliasOutcome.success()) {
            emitStep(sink, database, SchemaInitStep.ALIAS, "SUCCEEDED", aliasOutcome.message(),
                    null, null, aliasOutcome.result(), null);
        } else {
            emitStep(sink, database, SchemaInitStep.ALIAS, "FAILED", aliasOutcome.message(),
                    null, null, null, aliasOutcome.message());
        }

        if (!inferOutcome.success() || !aliasOutcome.success()) {
            markSkipped(sink, database, SchemaInitStep.INDEX);
            String reason = !inferOutcome.success() ? "推断失败: " + inferOutcome.message()
                    : "别名生成失败: " + aliasOutcome.message();
            return DbRunResult.fail(database, reason);
        }

        if (stop.get()) {
            return DbRunResult.fail(database, "已取消");
        }

        // --- INDEX ---
        emitStep(sink, database, SchemaInitStep.INDEX, "RUNNING", "正在建立向量索引…", null, null, null, null);
        try {
            ProgressListener indexProgress = (step, current, total, message) ->
                    sink.accept(SchemaInitSseEvent.progress(database, SchemaInitStep.INDEX, current, total, message));
            SchemaVectorService.IndexResult indexResult =
                    schemaVectorService.indexDatasource(datasourceId, database, indexProgress);
            emitStep(sink, database, SchemaInitStep.INDEX, "SUCCEEDED",
                    "已索引 " + indexResult.getIndexedCount() + " 张表",
                    indexResult.getIndexedCount(), indexResult.getIndexedCount(),
                    indexResult.toMap(), null);
            return DbRunResult.ok(database, syncResult.tableCount());
        } catch (Exception ex) {
            String msg = friendly(ex);
            emitStep(sink, database, SchemaInitStep.INDEX, "FAILED", msg, null, null, null, msg);
            return DbRunResult.fail(database, "索引失败: " + msg);
        }
    }

    private static void emitStep(Consumer<SchemaInitSseEvent> sink, String database, SchemaInitStep step,
                                 String status, String message, Integer current, Integer total,
                                 Object result, String error) {
        sink.accept(SchemaInitSseEvent.step(database, step, status, message, current, total, result, error));
    }

    private static void markSkipped(Consumer<SchemaInitSseEvent> sink, String database, SchemaInitStep... steps) {
        for (SchemaInitStep step : steps) {
            emitStep(sink, database, step, "PENDING", "已跳过", null, null, null, null);
        }
    }

    private static String friendly(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String msg = ex.getMessage();
        return StringUtils.hasText(msg) ? msg : ex.getClass().getSimpleName();
    }

    private record StepOutcome(boolean success, Map<String, Object> result, String message) {
        static StepOutcome ok(Map<String, Object> result, String message) {
            return new StepOutcome(true, result, message);
        }

        static StepOutcome fail(String message) {
            return new StepOutcome(false, null, message);
        }
    }

    private record DbRunResult(String database, boolean success, String message, int tableCount) {
        static DbRunResult ok(String database, int tableCount) {
            return new DbRunResult(database, true, "完成", tableCount);
        }

        static DbRunResult fail(String database, String message) {
            return new DbRunResult(database, false, message, 0);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("database", database);
            map.put("success", success);
            map.put("message", message);
            map.put("tableCount", tableCount);
            return map;
        }
    }
}
