package com.dbsidekick.ai.milvus;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.config.SettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Schema 向量库：集合 {@code schema_chunks}。
 */
@Component
public class MilvusSchemaStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusSchemaStore.class);
    private static final String VECTOR_FIELD = "vector";
    private static final long LOAD_TIMEOUT_MS = 30_000L;
    /** 启动探活和提问重试都用短超时，避免把窗口或这一轮问答拖住。 */
    private static final int PROBE_TIMEOUT_MS = 2000;
    public static final String NOT_CONNECTED = "向量库未连接，请检查 Milvus 是否启动";
    private static final List<String> OUTPUT_FIELDS = List.of(
            "id", "content", "datasourceId", "dbName", "tableName", "objectType");

    private final SidekickAiProperties aiProperties;
    private final SettingsService settingsService;
    private final Object lock = new Object();
    private volatile MilvusClientV2 client;
    private volatile boolean ready;
    private final AtomicBoolean milvusReady = new AtomicBoolean(false);
    private final AtomicBoolean probeFinished = new AtomicBoolean(false);
    private final AtomicBoolean probeStarted = new AtomicBoolean(false);

    public MilvusSchemaStore(SidekickAiProperties aiProperties, @Lazy SettingsService settingsService) {
        this.aiProperties = aiProperties;
        this.settingsService = settingsService;
    }

    /**
     * Spring 已经打出 Started 之后才探活。这里只拉起线程，连接放在后台，不挡住窗口。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void probeAfterStartup() {
        if (!probeStarted.compareAndSet(false, true)) {
            return;
        }
        Thread probe = new Thread(this::probeInBackground, "dbsidekick-milvus-probe");
        probe.setDaemon(true);
        probe.start();
    }

    private void probeInBackground() {
        try {
            log.info("[Sidekick] Milvus 探活开始");
            boolean existed = connectAndPrepare(PROBE_TIMEOUT_MS);
            String name = collectionName();
            if (existed) {
                log.info("[Sidekick] collection {} ready", name);
            } else {
                log.info("[Sidekick] collection {} created", name);
            }
            Thread load = new Thread(() -> tryLoadAsync(name), "dbsidekick-milvus-load");
            load.setDaemon(true);
            load.start();
        } catch (Exception ex) {
            markDown();
            log.warn("[Sidekick] Milvus not reachable, schema_chunks skipped: {}", ex.getMessage());
        } finally {
            probeFinished.set(true);
        }
    }

    public boolean isReady() {
        return milvusReady.get();
    }

    public boolean isProbeFinished() {
        return probeFinished.get();
    }

    /** 检索前确认连得上。刚才没连上时再试一次，Milvus 后启动也不用重启应用。 */
    public void requireConnected() {
        if (milvusReady.get()) {
            return;
        }
        try {
            connectAndPrepare(PROBE_TIMEOUT_MS);
            log.info("[Sidekick] Milvus 提问时重新连上");
        } catch (Exception ex) {
            markDown();
            log.warn("[Sidekick] Milvus not reachable, schema_chunks skipped: {}", ex.getMessage());
            throw new IllegalStateException(NOT_CONNECTED);
        }
    }

    /** 只探测地址，不替换当前连接。成功返回 null。 */
    public String probe(String host, int port) {
        MilvusClientV2 neu = null;
        try {
            neu = new MilvusClientV2(ConnectConfig.builder()
                    .uri("http://" + host + ":" + port)
                    .connectTimeoutMs(4000)
                    .build());
            neu.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName())
                    .build());
            return null;
        } catch (Exception ex) {
            return ex.getMessage() == null ? "无法连接 Milvus" : ex.getMessage();
        } finally {
            closeQuietly(neu);
        }
    }

    /**
     * 热切换：用当前 Settings 中的 host/port/collection 重建连接。
     * 失败则保留旧连接并返回 false。
     */
    public boolean reconnect() {
        synchronized (lock) {
            String host = settingsService.milvusHost();
            int port = settingsService.milvusPort();
            MilvusClientV2 old = this.client;
            MilvusClientV2 neu = null;
            try {
                SidekickAiProperties.Milvus yml = milvusCfg();
                String uri = "http://" + host + ":" + port;
                neu = new MilvusClientV2(ConnectConfig.builder()
                        .uri(uri)
                        .connectTimeoutMs(yml.getConnectTimeoutMs() > 0 ? yml.getConnectTimeoutMs() : 8000)
                        .build());
                // 探测连通性
                neu.hasCollection(HasCollectionReq.builder()
                        .collectionName(settingsService.milvusCollection())
                        .build());
                this.client = neu;
                ensureCollectionExists();
                tryLoadAsync(collectionName());
                markUp();
                closeQuietly(old);
                log.info("[Sidekick] milvus reconnected: host={} port={}", host, port);
                return true;
            } catch (Exception ex) {
                closeQuietly(neu);
                log.warn("[Sidekick] milvus reconnect failed, keep old connection: host={} port={} err={}",
                        host, port, ex.getMessage());
                return false;
            }
        }
    }

    private static void closeQuietly(MilvusClientV2 c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 集合不存在则创建（含索引），不强制 load。 */
    public synchronized void ensureCollection() {
        ensureCollectionExists();
        tryLoadAsync(collectionName());
        markUp();
    }

    private void ensureCollectionExists() {
        MilvusClientV2 milvus = client();
        String name = collectionName();
        boolean exists = Boolean.TRUE.equals(milvus.hasCollection(HasCollectionReq.builder()
                .collectionName(name)
                .build()));
        if (!exists) {
            createCollection(milvus, name, dimension());
        }
    }

    private void tryLoadAsync(String name) {
        try {
            MilvusClientV2 milvus = client();
            milvus.loadCollection(LoadCollectionReq.builder()
                    .collectionName(name)
                    .sync(Boolean.FALSE)
                    .timeout(LOAD_TIMEOUT_MS)
                    .build());
        } catch (Exception ex) {
            log.debug("[Sidekick][milvus] async load 跳过: {}", ex.getMessage());
        }
    }

    public boolean collectionExists() {
        try {
            return Boolean.TRUE.equals(client().hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName())
                    .build()));
        } catch (Exception ex) {
            return false;
        }
    }

    public void upsert(List<SchemaChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        MilvusClientV2 milvus = client();
        ensureCollection();
        // 先按 id 删除再插入，模拟 upsert
        List<String> ids = new ArrayList<>();
        for (SchemaChunk chunk : chunks) {
            if (StringUtils.hasText(chunk.getId())) {
                ids.add(chunk.getId());
            }
        }
        if (!ids.isEmpty()) {
            try {
                milvus.delete(DeleteReq.builder()
                        .collectionName(collectionName())
                        .ids(new ArrayList<>(ids))
                        .build());
            } catch (Exception ex) {
                log.debug("[Sidekick][milvus] upsert 预删除跳过: {}", ex.getMessage());
            }
        }

        List<JsonObject> data = new ArrayList<>();
        for (SchemaChunk chunk : chunks) {
            if (!StringUtils.hasText(chunk.getId()) || chunk.getVector() == null) {
                throw new IllegalArgumentException("SchemaChunk.id / vector 不能为空");
            }
            if (chunk.getVector().length != dimension()) {
                throw new IllegalArgumentException("向量维度必须为 " + dimension());
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", cut(chunk.getId(), 64));
            row.add("vector", toJsonArray(chunk.getVector()));
            row.addProperty("content", cut(nvl(chunk.getContent()), 8192));
            row.addProperty("datasourceId", cut(nvl(chunk.getDatasourceId()), 64));
            row.addProperty("dbName", cut(nvl(chunk.getDbName()), 128));
            row.addProperty("tableName", cut(nvl(chunk.getTableName()), 128));
            row.addProperty("objectType", "table");
            data.add(row);
        }
        milvus.insert(InsertReq.builder()
                .collectionName(collectionName())
                .data(data)
                .build());
        flush(milvus);
        // 不在每次 upsert 后 release+load（大批量索引会极慢）；由调用方或 search/rowCount 再 ensureLoaded
    }

    public long deleteByDatasource(String datasourceId) {
        return deleteByDatasource(datasourceId, null);
    }

    public long deleteByDatasource(String datasourceId, String dbName) {
        if (!StringUtils.hasText(datasourceId)) {
            return 0L;
        }
        MilvusClientV2 milvus = client();
        if (!collectionExists()) {
            return 0L;
        }
        ensureLoaded(milvus, collectionName());
        List<Object> ids = listIdsByDatasource(milvus, datasourceId, dbName);
        if (ids.isEmpty()) {
            return 0L;
        }
        // Milvus Lite 仅支持按主键删除
        DeleteResp resp = milvus.delete(DeleteReq.builder()
                .collectionName(collectionName())
                .ids(ids)
                .build());
        flush(milvus);
        return resp == null ? ids.size() : Math.max(resp.getDeleteCnt(), ids.size());
    }

    /** 统计某数据源在向量库中的条目数（删除预检用）。 */
    public long countByDatasource(String datasourceId) {
        if (!StringUtils.hasText(datasourceId)) {
            return 0L;
        }
        try {
            if (!collectionExists()) {
                return 0L;
            }
            MilvusClientV2 milvus = client();
            ensureLoaded(milvus, collectionName());
            return listIdsByDatasource(milvus, datasourceId.trim()).size();
        } catch (Exception ex) {
            log.warn("[Sidekick][milvus] countByDatasource failed: {}", ex.getMessage());
            return 0L;
        }
    }

    /** 索引批量写完后调用，确保 query/search/rowCount 可见最新数据。 */
    public void refresh() {
        MilvusClientV2 milvus = client();
        ensureCollection();
        refreshLoad(milvus, collectionName());
    }

    public List<SchemaHit> search(String datasourceId, float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length != dimension()) {
            throw new IllegalArgumentException("queryVector 维度必须为 " + dimension());
        }
        int k = Math.max(1, topK);
        requireConnected();
        MilvusClientV2 milvus = client();
        ensureCollection();
        ensureLoaded(milvus, collectionName());

        // 先不过滤标量（兼容 Lite），取更大 topK 再内存过滤 datasourceId
        int recall = StringUtils.hasText(datasourceId) ? Math.max(k * 5, 20) : k;
        SearchReq.SearchReqBuilder builder = SearchReq.builder()
                .collectionName(collectionName())
                .data(Collections.singletonList(new FloatVec(toList(queryVector))))
                .annsField(VECTOR_FIELD)
                .topK(recall)
                .outputFields(OUTPUT_FIELDS)
                .metricType(IndexParam.MetricType.L2)
                .consistencyLevel(ConsistencyLevel.STRONG);
        SearchResp resp = milvus.search(builder.build());
        List<SchemaHit> hits = mapSearchHits(resp);
        if (!StringUtils.hasText(datasourceId)) {
            return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
        }
        List<SchemaHit> filtered = new ArrayList<>();
        // mapSearchHits 不含 datasourceId；从 entity 再扫一遍
        filtered.addAll(filterHitsByDatasource(resp, datasourceId, k));
        return filtered;
    }

    /**
     * 诊断：按 datasource + 表名取 Milvus 中存的完整 content 卡片。
     */
    public String getTableContent(String datasourceId, String tableName) {
        if (!StringUtils.hasText(datasourceId) || !StringUtils.hasText(tableName)) {
            return null;
        }
        String ds = datasourceId.trim();
        String tn = tableName.trim();
        MilvusClientV2 milvus = client();
        ensureCollection();
        ensureLoaded(milvus, collectionName());
        try {
            QueryResp resp = milvus.query(QueryReq.builder()
                    .collectionName(collectionName())
                    .filter("objectType == \"table\"")
                    .outputFields(List.of("content", "datasourceId", "tableName", "dbName"))
                    .limit(16384)
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
            if (resp == null || resp.getQueryResults() == null) {
                return null;
            }
            for (QueryResp.QueryResult row : resp.getQueryResults()) {
                Map<String, Object> entity = row.getEntity();
                if (entity == null) {
                    continue;
                }
                if (ds.equals(scalar(entity, "datasourceId")) && tn.equalsIgnoreCase(scalar(entity, "tableName"))) {
                    return scalar(entity, "content");
                }
            }
            return null;
        } catch (Exception ex) {
            throw new IllegalStateException("查询表卡片失败: " + ex.getMessage(), ex);
        }
    }

    public long rowCount() {
        try {
            if (!collectionExists()) {
                return 0L;
            }
            MilvusClientV2 milvus = client();
            ensureLoaded(milvus, collectionName());
            // Lite 上 getCollectionStats 常返回过期的 0，以 query 为准
            QueryResp resp = milvus.query(QueryReq.builder()
                    .collectionName(collectionName())
                    .filter("objectType == \"table\"")
                    .outputFields(List.of("id"))
                    .limit(16384)
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
            if (resp != null && resp.getQueryResults() != null) {
                return resp.getQueryResults().size();
            }
            GetCollectionStatsResp stats = milvus.getCollectionStats(GetCollectionStatsReq.builder()
                    .collectionName(collectionName())
                    .build());
            if (stats != null && stats.getNumOfEntities() != null) {
                return stats.getNumOfEntities();
            }
            return 0L;
        } catch (Exception ex) {
            log.warn("[Sidekick][milvus] rowCount 失败: {}", ex.getMessage());
            return -1L;
        }
    }

    private List<Object> listIdsByDatasource(MilvusClientV2 milvus, String datasourceId) {
        return listIdsByDatasource(milvus, datasourceId, null);
    }

    private List<Object> listIdsByDatasource(MilvusClientV2 milvus, String datasourceId, String dbName) {
        List<Object> ids = new ArrayList<>();
        try {
            QueryResp resp = milvus.query(QueryReq.builder()
                    .collectionName(collectionName())
                    .filter("objectType == \"table\"")
                    .outputFields(List.of("id", "datasourceId", "dbName"))
                    .limit(16384)
                    .consistencyLevel(ConsistencyLevel.STRONG)
                    .build());
            if (resp == null || resp.getQueryResults() == null) {
                return ids;
            }
            for (QueryResp.QueryResult row : resp.getQueryResults()) {
                Map<String, Object> entity = row.getEntity();
                if (entity == null) {
                    continue;
                }
                String ds = scalar(entity, "datasourceId");
                if (!datasourceId.equals(ds)) {
                    continue;
                }
                if (StringUtils.hasText(dbName)) {
                    String hitDb = scalar(entity, "dbName");
                    if (hitDb == null || !dbName.trim().equalsIgnoreCase(hitDb)) {
                        continue;
                    }
                }
                Object id = entity.get("id");
                if (id != null) {
                    ids.add(id);
                }
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][milvus] listIdsByDatasource 失败: {}", ex.getMessage());
        }
        return ids;
    }

    private List<SchemaHit> filterHitsByDatasource(SearchResp resp, String datasourceId, int topK) {
        List<SchemaHit> hits = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return hits;
        }
        List<SearchResp.SearchResult> first = resp.getSearchResults().get(0);
        if (first == null) {
            return hits;
        }
        for (SearchResp.SearchResult r : first) {
            Map<String, Object> entity = r.getEntity();
            if (entity == null) {
                continue;
            }
            if (!datasourceId.equals(scalar(entity, "datasourceId"))) {
                continue;
            }
            String activeDb = com.dbsidekick.datasource.ActiveDatabase.get();
            if (org.springframework.util.StringUtils.hasText(activeDb)) {
                String hitDb = scalar(entity, "dbName");
                if (hitDb == null || !activeDb.equalsIgnoreCase(hitDb)) {
                    continue;
                }
            }
            float score = r.getScore() == null ? 0f : r.getScore();
            hits.add(new SchemaHit(scalar(entity, "tableName"), scalar(entity, "dbName"),
                    scalar(entity, "content"), score));
            if (hits.size() >= topK) {
                break;
            }
        }
        return hits;
    }

    private void refreshLoad(MilvusClientV2 milvus, String name) {
        try {
            milvus.releaseCollection(ReleaseCollectionReq.builder().collectionName(name).build());
        } catch (Exception ex) {
            log.debug("[Sidekick][milvus] release 跳过: {}", ex.getMessage());
        }
        ensureLoaded(milvus, name);
    }

    private void flush(MilvusClientV2 milvus) {
        try {
            milvus.flush(FlushReq.builder()
                    .collectionNames(List.of(collectionName()))
                    .waitFlushedTimeoutMs(10_000L)
                    .build());
        } catch (Exception ex) {
            log.debug("[Sidekick][milvus] flush 跳过: {}", ex.getMessage());
        }
    }

    /** 只读后台探活结果，健康检查不再自己去连，避免一次请求又等满超时。 */
    public boolean ping() {
        return milvusReady.get();
    }

    public String collectionName() {
        String name = settingsService.milvusCollection();
        return StringUtils.hasText(name) ? name.trim() : "schema_chunks";
    }

    private void createCollection(MilvusClientV2 milvus, String name, int dim) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(List.of(
                        pk("id"),
                        vectorField(VECTOR_FIELD, dim),
                        varchar("content", 8192),
                        varchar("datasourceId", 64),
                        varchar("dbName", 128),
                        varchar("tableName", 128),
                        varchar("objectType", 32)
                ))
                .build();

        IndexParam vectorIndex = IndexParam.builder()
                .fieldName(VECTOR_FIELD)
                .indexName("idx_vector")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.L2)
                .extraParams(Map.of("nlist", 128))
                .build();

        // 标量索引在部分 Lite/旧版上会报 Trie/INVERTED 错误，过滤靠内存/表达式
        milvus.createCollection(CreateCollectionReq.builder()
                .collectionName(name)
                .description("DBSidekick schema table cards")
                .collectionSchema(schema)
                .indexParams(List.of(vectorIndex))
                .build());
    }

    private void ensureLoaded(MilvusClientV2 milvus, String name) {
        try {
            Boolean loaded = milvus.getLoadState(GetLoadStateReq.builder().collectionName(name).build());
            if (Boolean.TRUE.equals(loaded)) {
                return;
            }
        } catch (Exception ex) {
            log.debug("[Sidekick][milvus] getLoadState: {}", ex.getMessage());
        }
        // 异步 load + 短轮询，避免 Lite sync load 卡死
        try {
            milvus.loadCollection(LoadCollectionReq.builder()
                    .collectionName(name)
                    .sync(Boolean.FALSE)
                    .timeout(LOAD_TIMEOUT_MS)
                    .build());
            long deadline = System.currentTimeMillis() + Math.min(LOAD_TIMEOUT_MS, 15_000L);
            while (System.currentTimeMillis() < deadline) {
                try {
                    Boolean loaded = milvus.getLoadState(GetLoadStateReq.builder().collectionName(name).build());
                    if (Boolean.TRUE.equals(loaded)) {
                        return;
                    }
                } catch (Exception ignored) {
                    // continue
                }
                try {
                    Thread.sleep(400L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            log.warn("[Sidekick][milvus] loadCollection 未在时限内完成，继续（检索可能稍后可用）");
        } catch (Exception e) {
            log.warn("[Sidekick][milvus] loadCollection 失败（将在下次操作重试）: {}", e.getMessage());
        }
    }

    private MilvusClientV2 client() {
        if (client != null) {
            return client;
        }
        synchronized (lock) {
            if (client != null) {
                return client;
            }
            SidekickAiProperties.Milvus yml = milvusCfg();
            int timeout = yml.getConnectTimeoutMs() > 0 ? yml.getConnectTimeoutMs() : 8000;
            client = openClient(timeout);
            return client;
        }
    }

    /**
     * @return 连接前集合是否已经存在
     */
    private boolean connectAndPrepare(int timeoutMs) {
        synchronized (lock) {
            MilvusClientV2 neu = openClient(timeoutMs);
            MilvusClientV2 old = this.client;
            this.client = neu;
            boolean existed;
            try {
                existed = collectionExistsOn(neu);
                ensureCollectionExists();
            } catch (Exception ex) {
                this.client = old;
                closeQuietly(neu);
                if (ex instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(ex.getMessage(), ex);
            }
            if (old != null && old != neu) {
                closeQuietly(old);
            }
            markUp();
            return existed;
        }
    }

    private boolean collectionExistsOn(MilvusClientV2 milvus) {
        return Boolean.TRUE.equals(milvus.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName())
                .build()));
    }

    private MilvusClientV2 openClient(int timeoutMs) {
        String uri = "http://" + settingsService.milvusHost() + ":" + settingsService.milvusPort();
        return new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .connectTimeoutMs(timeoutMs)
                .build());
    }

    private void markUp() {
        ready = true;
        milvusReady.set(true);
        probeFinished.set(true);
    }

    private void markDown() {
        ready = false;
        milvusReady.set(false);
    }

    private SidekickAiProperties.Milvus milvusCfg() {
        return aiProperties.getMilvus() == null ? new SidekickAiProperties.Milvus() : aiProperties.getMilvus();
    }

    private int dimension() {
        return settingsService.embeddingDimension();
    }

    private List<SchemaHit> mapSearchHits(SearchResp resp) {
        List<SchemaHit> hits = new ArrayList<>();
        if (resp == null || resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return hits;
        }
        List<SearchResp.SearchResult> first = resp.getSearchResults().get(0);
        if (first == null) {
            return hits;
        }
        for (SearchResp.SearchResult r : first) {
            Map<String, Object> entity = r.getEntity();
            String tableName = scalar(entity, "tableName");
            String dbName = scalar(entity, "dbName");
            String content = scalar(entity, "content");
            float score = r.getScore() == null ? 0f : r.getScore();
            hits.add(new SchemaHit(tableName, dbName, content, score));
        }
        return hits;
    }

    private static String scalar(Map<String, Object> entity, String key) {
        if (entity == null || entity.get(key) == null) {
            return null;
        }
        return String.valueOf(entity.get(key));
    }

    private static CreateCollectionReq.FieldSchema pk(String name) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.VarChar)
                .maxLength(64)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .build();
    }

    private static CreateCollectionReq.FieldSchema vectorField(String name, int dim) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.FloatVector)
                .dimension(dim)
                .build();
    }

    private static CreateCollectionReq.FieldSchema varchar(String name, int maxLength) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .build();
    }

    private static JsonArray toJsonArray(float[] vector) {
        JsonArray arr = new JsonArray(vector.length);
        for (float v : vector) {
            arr.add(v);
        }
        return arr;
    }

    private static List<Float> toList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
