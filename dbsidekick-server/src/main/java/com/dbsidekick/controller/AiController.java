package com.dbsidekick.controller;

import com.dbsidekick.ai.embedding.LocalEmbeddingService;
import com.dbsidekick.ai.milvus.MilvusSchemaStore;
import com.dbsidekick.ai.milvus.SchemaChunk;
import com.dbsidekick.ai.milvus.SchemaHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final LocalEmbeddingService embeddingService;
    private final MilvusSchemaStore milvusSchemaStore;

    public AiController(LocalEmbeddingService embeddingService, MilvusSchemaStore milvusSchemaStore) {
        this.embeddingService = embeddingService;
        this.milvusSchemaStore = milvusSchemaStore;
    }

    @GetMapping("/embedding/health")
    public Map<String, Object> embeddingHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", embeddingService.provider());
        body.put("modelPath", embeddingService.modelPath());
        try {
            boolean ready = embeddingService.ensureReady();
            body.put("loaded", ready);
        } catch (Exception ex) {
            body.put("loaded", false);
            body.put("error", ex.getMessage());
        }
        return body;
    }

    @GetMapping("/embedding/test")
    public Map<String, Object> embeddingTest(@RequestParam(value = "text", defaultValue = "订单") String text) {
        long start = System.currentTimeMillis();
        try {
            float[] vector = embeddingService.embed(text);
            List<Float> first5 = new ArrayList<>();
            for (int i = 0; i < Math.min(5, vector.length); i++) {
                first5.add(vector[i]);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("provider", embeddingService.provider());
            body.put("dimension", vector.length);
            body.put("elapsedMs", System.currentTimeMillis() - start);
            body.put("first5", first5);
            return body;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @GetMapping("/milvus/health")
    public Map<String, Object> milvusHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean reachable = milvusSchemaStore.ping();
        body.put("reachable", reachable);
        body.put("collection", milvusSchemaStore.collectionName());
        if (!reachable) {
            body.put("exists", false);
            body.put("rowCount", 0);
            return body;
        }
        try {
            body.put("exists", milvusSchemaStore.collectionExists());
            body.put("rowCount", milvusSchemaStore.rowCount());
        } catch (Exception ex) {
            body.put("exists", false);
            body.put("rowCount", 0);
            body.put("error", ex.getMessage());
        }
        return body;
    }

    @PostMapping("/milvus/probe")
    public Map<String, Object> milvusProbe(@RequestBody Map<String, Object> body) {
        String host = body == null || body.get("host") == null ? "" : String.valueOf(body.get("host")).trim();
        int port = 19530;
        if (body != null && body.get("port") != null) {
            try {
                port = Integer.parseInt(String.valueOf(body.get("port")).trim());
            } catch (NumberFormatException ex) {
                port = -1;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        if (host.isEmpty() || port < 1 || port > 65535) {
            resp.put("success", false);
            resp.put("message", "请填写有效的主机和端口");
            return resp;
        }
        String error = milvusSchemaStore.probe(host, port);
        resp.put("success", error == null);
        if (error != null) {
            resp.put("message", error);
        }
        return resp;
    }

    @PostMapping("/milvus/test-upsert")
    public Map<String, Object> milvusTestUpsert() {
        try {
            float[] v1 = fakeVector(768, 1);
            float[] v2 = fakeVector(768, 2);
            List<SchemaChunk> chunks = new ArrayList<>();
            chunks.add(chunk("t1", "表：orders 订单表", "test", "test", "orders", v1));
            chunks.add(chunk("t2", "表：users 用户表", "test", "test", "users", v2));
            milvusSchemaStore.upsert(chunks);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("upserted", 2);
            return body;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/milvus/test-clean")
    public Map<String, Object> milvusTestClean(
            @RequestParam(value = "datasourceId", defaultValue = "test") String datasourceId) {
        try {
            long deleted = milvusSchemaStore.deleteByDatasource(datasourceId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deleted", deleted);
            return body;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/milvus/test-search")
    public List<SchemaHit> milvusTestSearch(@RequestBody Map<String, Object> body) {
        try {
            String datasourceId = body == null ? null : String.valueOf(body.getOrDefault("datasourceId", "test"));
            int topK = 5;
            if (body != null && body.get("topK") instanceof Number) {
                topK = ((Number) body.get("topK")).intValue();
            }
            float[] queryVector = parseVector(body == null ? null : body.get("queryVector"), 768);
            return milvusSchemaStore.search(datasourceId, queryVector, topK);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    private static SchemaChunk chunk(String id, String content, String ds, String db, String table, float[] vector) {
        SchemaChunk c = new SchemaChunk();
        c.setId(id);
        c.setContent(content);
        c.setDatasourceId(ds);
        c.setDbName(db);
        c.setTableName(table);
        c.setVector(vector);
        return c;
    }

    /** 生成可复现的伪向量并 L2 归一化。 */
    static float[] fakeVector(int dim, int seed) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) Math.sin((i + 1) * 0.01 * seed) * 0.1f + seed * 0.001f;
        }
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static float[] parseVector(Object raw, int expected) {
        if (raw instanceof List<?> list) {
            float[] v = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object o = list.get(i);
                v[i] = o instanceof Number ? ((Number) o).floatValue() : Float.parseFloat(String.valueOf(o));
            }
            if (v.length != expected) {
                throw new IllegalArgumentException("queryVector 维度必须为 " + expected);
            }
            return v;
        }
        // 未传则用与 t1 同分布的假向量，方便冒烟
        return fakeVector(expected, 1);
    }
}
