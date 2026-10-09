package com.dbsidekick.ai.embedding;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.config.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 本地 Embedding：ONNX（bge-base-zh-v1.5）或 Ollama HTTP。
 * 配置优先读 SettingsService。
 */
@Lazy
@Service
public class LocalEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingService.class);

    private final SidekickAiProperties aiProperties;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object lock = new Object();

    private volatile EmbeddingModel onnxModel;
    private volatile boolean onnxLoaded;

    public LocalEmbeddingService(SidekickAiProperties aiProperties, @Lazy SettingsService settingsService) {
        this.aiProperties = aiProperties;
        this.settingsService = settingsService;
    }

    public void invalidateCache() {
        reload();
    }

    /** 热切换：释放 ONNX，下次 embed 按当前 Settings 懒加载。 */
    public void reload() {
        synchronized (lock) {
            onnxModel = null;
            onnxLoaded = false;
        }
        log.info("[Sidekick] embedding reloaded: provider={}", provider());
    }

    public float[] embed(String text) {
        long start = System.currentTimeMillis();
        float[] vector;
        String provider = provider();
        if ("ollama".equalsIgnoreCase(provider)) {
            vector = embedOllama(text);
        } else {
            vector = embedOnnx(text);
        }
        long elapsed = System.currentTimeMillis() - start;
        if (log.isDebugEnabled()) {
            log.debug("[Sidekick][embedding] provider={} dim={} elapsedMs={} first5={}",
                    provider, vector.length, elapsed, first5(vector));
        }
        return vector;
    }

    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        if (texts == null) {
            return out;
        }
        for (String text : texts) {
            out.add(embed(text));
        }
        return out;
    }

    public int dimension() {
        return settingsService.embeddingDimension();
    }

    public String provider() {
        String p = settingsService.embeddingProvider();
        return StringUtils.hasText(p) ? p.trim().toLowerCase(Locale.ROOT) : "onnx";
    }

    public String modelPath() {
        if ("ollama".equalsIgnoreCase(provider())) {
            return settingsService.embeddingOllamaUrl() + " (" + settingsService.embeddingOllamaModel() + ")";
        }
        return settingsService.embeddingOnnxPath();
    }

    /** 尝试加载（ONNX）或探测模型文件；供 health 使用。 */
    public boolean ensureReady() {
        if ("ollama".equalsIgnoreCase(provider())) {
            return StringUtils.hasText(settingsService.embeddingOllamaUrl());
        }
        ensureOnnxLoaded();
        return onnxLoaded;
    }

    public boolean isLoaded() {
        if ("ollama".equalsIgnoreCase(provider())) {
            return true;
        }
        return onnxLoaded;
    }

    private float[] embedOnnx(String text) {
        EmbeddingModel model = ensureOnnxLoaded();
        String input = text == null ? "" : text;
        int maxSeq = cfg().getMaxSequenceLength() <= 0 ? 512 : cfg().getMaxSequenceLength();
        String fitted = clipForModel(input, maxSeq);
        if (fitted.length() < input.length()) {
            log.warn("[Sidekick][embedding] 输入超过模型上限 {}，已截断 {}→{} 字",
                    maxSeq, input.length(), fitted.length());
        }
        Embedding embedding = model.embed(fitted).content();
        float[] vector = embedding.vector();
        int expected = dimension();
        if (expected > 0 && vector.length != expected) {
            throw new IllegalStateException("嵌入维度为 " + vector.length + "，与配置 " + expected + " 不一致");
        }
        l2Normalize(vector);
        return vector;
    }

    private EmbeddingModel ensureOnnxLoaded() {
        if (onnxModel != null) {
            return onnxModel;
        }
        synchronized (lock) {
            if (onnxModel != null) {
                return onnxModel;
            }
            Path dir = resolveDir();
            Path onnx = dir.resolve(StringUtils.hasText(cfg().getOnnxFile()) ? cfg().getOnnxFile() : "model.onnx");
            Path tokenizer = dir.resolve(StringUtils.hasText(cfg().getTokenizerFile()) ? cfg().getTokenizerFile() : "tokenizer.json");
            try {
                if (!Files.isRegularFile(onnx) || !Files.isRegularFile(tokenizer)) {
                    throw new IllegalStateException(
                            "未找到 BGE ONNX 模型文件，请将 model.onnx 与 tokenizer.json 放到 " + dir);
                }
                PoolingMode pooling = parsePooling(cfg().getPooling());
                log.info("[Sidekick][embedding] 加载 ONNX {} pooling={} dim={}", onnx, pooling, dimension());
                onnxModel = new OnnxEmbeddingModel(onnx, tokenizer, pooling);
                onnxLoaded = true;
                return onnxModel;
            } catch (Exception ex) {
                onnxLoaded = false;
                log.error("[Sidekick][embedding] ONNX 加载失败: {}", ex.getMessage(), ex);
                throw new IllegalStateException("ONNX Embedding 加载失败: " + ex.getMessage(), ex);
            }
        }
    }

    private float[] embedOllama(String text) {
        String base = settingsService.embeddingOllamaUrl();
        if (!StringUtils.hasText(base)) {
            throw new IllegalStateException("Ollama 地址未配置");
        }
        String url = base.replaceAll("/+$", "") + "/api/embeddings";
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", settingsService.embeddingOllamaModel());
            body.put("prompt", text == null ? "" : text);

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] payload = objectMapper.writeValueAsBytes(body);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            JsonNode root = objectMapper.readTree(stream == null ? "{}".getBytes(StandardCharsets.UTF_8) : stream.readAllBytes());
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("Ollama HTTP " + code + ": " + root);
            }
            JsonNode emb = root.get("embedding");
            if (emb == null || !emb.isArray()) {
                throw new IllegalStateException("Ollama 响应缺少 embedding 数组: " + root);
            }
            float[] vector = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                vector[i] = (float) emb.get(i).asDouble();
            }
            int expected = dimension();
            if (expected > 0 && vector.length != expected) {
                throw new IllegalStateException("嵌入维度为 " + vector.length + "，与配置 " + expected + " 不一致");
            }
            l2Normalize(vector);
            return vector;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[Sidekick][embedding] Ollama 调用失败: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Ollama Embedding 失败: " + ex.getMessage(), ex);
        }
    }

    private SidekickAiProperties.Embedding cfg() {
        return aiProperties.getEmbedding() == null
                ? new SidekickAiProperties.Embedding()
                : aiProperties.getEmbedding();
    }

    Path resolveDir() {
        String dir = settingsService.embeddingOnnxPath();
        if (!StringUtils.hasText(dir)) {
            throw new IllegalStateException("尚未选择向量模型目录");
        }
        return Path.of(dir.trim()).toAbsolutePath().normalize();
    }

    static String clipForModel(String text, int maxSequenceLength) {
        String input = text == null ? "" : text;
        int maxChars = Math.max(32, maxSequenceLength - 2);
        if (input.length() <= maxChars) {
            return input;
        }
        return input.substring(0, maxChars);
    }

    static PoolingMode parsePooling(String pooling) {
        if (!StringUtils.hasText(pooling)) {
            return PoolingMode.CLS;
        }
        String value = pooling.trim().toUpperCase(Locale.ROOT);
        if ("MEAN".equals(value) || "AVG".equals(value) || "AVERAGE".equals(value)) {
            return PoolingMode.MEAN;
        }
        return PoolingMode.CLS;
    }

    static void l2Normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm <= 0) {
            return;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
    }

    static List<Float> first5(float[] vector) {
        List<Float> list = new ArrayList<>(5);
        if (vector == null) {
            return list;
        }
        for (int i = 0; i < Math.min(5, vector.length); i++) {
            list.add(vector[i]);
        }
        return list;
    }
}
