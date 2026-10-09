package com.dbsidekick.config;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.sqlguard.SqlRuntimeConfig;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 应用配置：SQLite 覆盖 application.yml 默认值。
 */
@Service
public class SettingsService {

    public static final String LLM_PROVIDER = "llm.provider";
    public static final String LLM_BASE_URL = "llm.baseUrl";
    public static final String LLM_API_KEY = "llm.apiKey";
    public static final String LLM_MODEL = "llm.model";
    public static final String LLM_TEMPERATURE = "llm.temperature";

    public static final String EMB_PROVIDER = "embedding.provider";
    public static final String EMB_ONNX_PATH = "embedding.onnxModelPath";
    public static final String EMB_OLLAMA_URL = "embedding.ollamaUrl";
    public static final String EMB_OLLAMA_MODEL = "embedding.ollamaModel";
    public static final String EMB_DIMENSION = "embedding.dimension";

    public static final String MILVUS_HOST = "milvus.host";
    public static final String MILVUS_PORT = "milvus.port";
    public static final String MILVUS_COLLECTION = "milvus.collection";

    public static final String SQL_LIMIT_ENABLED = SqlRuntimeConfig.KEY_LIMIT_ENABLED;
    public static final String SQL_DEFAULT_LIMIT = SqlRuntimeConfig.KEY_DEFAULT_LIMIT;
    public static final String SQL_MAX_STATEMENTS = SqlRuntimeConfig.KEY_MAX_STATEMENTS;
    public static final String SQL_QUERY_TIMEOUT = SqlRuntimeConfig.KEY_QUERY_TIMEOUT;
    public static final String SETUP_COMPLETED = "setup.completed";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SqliteInitializer sqliteInitializer;
    private final SidekickAiProperties aiProperties;

    public SettingsService(SqliteInitializer sqliteInitializer, SidekickAiProperties aiProperties) {
        this.sqliteInitializer = sqliteInitializer;
        this.aiProperties = aiProperties;
    }

    public String get(String key, String defaultValue) {
        try {
            String v = loadRaw(key);
            return v != null ? v : defaultValue;
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    public void set(String key, String value) throws Exception {
        String now = TS.format(Instant.now());
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO app_settings(key, value, updated_at) VALUES(?, ?, ?)
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                     """)) {
            ps.setString(1, key);
            ps.setString(2, value == null ? "" : value);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    public void updateBatch(Map<String, String> map) throws Exception {
        if (map == null || map.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!StringUtils.hasText(e.getKey())) {
                continue;
            }
            // 空 apiKey：DeepSeek 表示不修改；OpenAI 兼容允许清空（本地 Ollama）
            if (LLM_API_KEY.equals(e.getKey()) && !StringUtils.hasText(e.getValue())) {
                if ("OPENAI_COMPATIBLE".equalsIgnoreCase(map.get(LLM_PROVIDER))) {
                    set(e.getKey(), "");
                }
                continue;
            }
            String value = e.getValue();
            if (SQL_MAX_STATEMENTS.equals(e.getKey()) && StringUtils.hasText(value)) {
                try {
                    int n = Integer.parseInt(value.trim());
                    value = String.valueOf(SqlRuntimeConfig.clampStatementsForSave(n));
                } catch (NumberFormatException ignored) {
                    value = String.valueOf(SqlRuntimeConfig.DEF_MAX_STATEMENTS);
                }
            }
            if (SQL_DEFAULT_LIMIT.equals(e.getKey()) && StringUtils.hasText(value)) {
                try {
                    int n = Integer.parseInt(value.trim());
                    if (n < 1) {
                        n = 1;
                    }
                    if (n > 10_000) {
                        n = 10_000;
                    }
                    value = String.valueOf(n);
                } catch (NumberFormatException ignored) {
                    value = String.valueOf(SqlRuntimeConfig.DEF_DEFAULT_LIMIT);
                }
            }
            if (SQL_QUERY_TIMEOUT.equals(e.getKey()) && StringUtils.hasText(value)) {
                try {
                    int n = Integer.parseInt(value.trim());
                    if (n < 1) {
                        n = 1;
                    }
                    if (n > 600) {
                        n = 600;
                    }
                    value = String.valueOf(n);
                } catch (NumberFormatException ignored) {
                    value = String.valueOf(SqlRuntimeConfig.DEF_QUERY_TIMEOUT);
                }
            }
            set(e.getKey(), value);
        }
    }

    /** 含默认值合并；apiKey 脱敏。 */
    public Map<String, Object> getAllMasked() {
        Map<String, Object> all = getAllEffective();
        String realKey = llmApiKey();
        all.put(LLM_API_KEY, maskKey(realKey));
        all.put("llm.apiKeyConfigured", StringUtils.hasText(realKey));
        all.put("dbPath", SqliteInitializer.DB_PATH.toAbsolutePath().toString());
        all.put("setupCompleted", "true".equalsIgnoreCase(get(SETUP_COMPLETED, "")));
        return all;
    }

    public Map<String, Object> getAllEffective() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(LLM_PROVIDER, llmProvider());
        m.put(LLM_BASE_URL, llmBaseUrl());
        m.put(LLM_API_KEY, llmApiKey());
        m.put(LLM_MODEL, llmModel());
        m.put(LLM_TEMPERATURE, String.valueOf(llmTemperature()));
        m.put(EMB_PROVIDER, embeddingProvider());
        m.put(EMB_ONNX_PATH, embeddingOnnxPath());
        m.put(EMB_OLLAMA_URL, embeddingOllamaUrl());
        m.put(EMB_OLLAMA_MODEL, embeddingOllamaModel());
        m.put(EMB_DIMENSION, String.valueOf(embeddingDimension()));
        m.put(MILVUS_HOST, milvusHost());
        m.put(MILVUS_PORT, String.valueOf(milvusPort()));
        m.put(MILVUS_COLLECTION, milvusCollection());
        m.put(SQL_LIMIT_ENABLED, get(SQL_LIMIT_ENABLED, String.valueOf(SqlRuntimeConfig.DEF_LIMIT_ENABLED)));
        m.put(SQL_DEFAULT_LIMIT, get(SQL_DEFAULT_LIMIT, String.valueOf(SqlRuntimeConfig.DEF_DEFAULT_LIMIT)));
        m.put(SQL_MAX_STATEMENTS, get(SQL_MAX_STATEMENTS, String.valueOf(SqlRuntimeConfig.DEF_MAX_STATEMENTS)));
        m.put(SQL_QUERY_TIMEOUT, get(SQL_QUERY_TIMEOUT, String.valueOf(SqlRuntimeConfig.DEF_QUERY_TIMEOUT)));
        return m;
    }

    public String llmProvider() {
        String raw = firstNonBlank(loadRaw(LLM_PROVIDER), "DEEPSEEK");
        if ("OPENAI_COMPATIBLE".equalsIgnoreCase(raw.trim())) {
            return "OPENAI_COMPATIBLE";
        }
        return "DEEPSEEK";
    }

    public String llmBaseUrl() {
        return firstNonBlank(loadRaw(LLM_BASE_URL), ymlLlm().getBaseUrl(), "https://api.deepseek.com");
    }

    public String llmApiKey() {
        if ("OPENAI_COMPATIBLE".equals(llmProvider())) {
            String stored = loadRaw(LLM_API_KEY);
            return stored == null ? "" : stored.trim();
        }
        return firstNonBlank(loadRaw(LLM_API_KEY), ymlApiKey(), "");
    }

    public String llmModel() {
        return firstNonBlank(loadRaw(LLM_MODEL), ymlLlm().getModel(), "deepseek-v4-pro");
    }

    public double llmTemperature() {
        String raw = loadRaw(LLM_TEMPERATURE);
        if (StringUtils.hasText(raw)) {
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return ymlLlm().getTemperature();
    }

    public String embeddingProvider() {
        return firstNonBlank(loadRaw(EMB_PROVIDER), ymlEmb().getProvider(), "onnx");
    }

    public String embeddingOnnxPath() {
        return firstNonBlank(loadRaw(EMB_ONNX_PATH), ymlEmb().getOnnxModelPath(), "");
    }

    public String embeddingOllamaUrl() {
        return firstNonBlank(loadRaw(EMB_OLLAMA_URL), ymlEmb().getOllamaUrl(), "http://localhost:11434");
    }

    public String embeddingOllamaModel() {
        return firstNonBlank(loadRaw(EMB_OLLAMA_MODEL), ymlEmb().getOllamaModel(), "bge-base-zh-1.5b");
    }

    public int embeddingDimension() {
        String raw = loadRaw(EMB_DIMENSION);
        if (StringUtils.hasText(raw)) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        int d = ymlEmb().getDimension();
        return d > 0 ? d : 768;
    }

    public String milvusHost() {
        return firstNonBlank(loadRaw(MILVUS_HOST), ymlMilvus().getHost(), "localhost");
    }

    public int milvusPort() {
        String raw = loadRaw(MILVUS_PORT);
        if (StringUtils.hasText(raw)) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        int p = ymlMilvus().getPort();
        return p > 0 ? p : 19530;
    }

    public String milvusCollection() {
        return firstNonBlank(loadRaw(MILVUS_COLLECTION), ymlMilvus().getCollection(), "schema_chunks");
    }

    private String loadRaw(String key) {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_settings WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("value");
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private SidekickAiProperties.Llm ymlLlm() {
        return aiProperties.getLlm() == null ? new SidekickAiProperties.Llm() : aiProperties.getLlm();
    }

    private SidekickAiProperties.Embedding ymlEmb() {
        return aiProperties.getEmbedding() == null
                ? new SidekickAiProperties.Embedding()
                : aiProperties.getEmbedding();
    }

    private SidekickAiProperties.Milvus ymlMilvus() {
        return aiProperties.getMilvus() == null ? new SidekickAiProperties.Milvus() : aiProperties.getMilvus();
    }

    private String ymlApiKey() {
        String fromEnv = System.getenv("DEEPSEEK_API_KEY");
        if (StringUtils.hasText(fromEnv)) {
            return fromEnv.trim();
        }
        return ymlLlm().getApiKey();
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return null;
    }

    private static String maskKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 8) {
            return "****";
        }
        return k.substring(0, 4) + "****" + k.substring(k.length() - 4);
    }
}
