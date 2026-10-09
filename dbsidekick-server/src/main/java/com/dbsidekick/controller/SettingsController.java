package com.dbsidekick.controller;

import com.dbsidekick.ai.embedding.LocalEmbeddingService;
import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.ai.milvus.MilvusSchemaStore;
import com.dbsidekick.config.SettingsService;
import com.dbsidekick.config.SqliteInitializer;
import java.awt.Desktop;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SettingsController {

    private final SettingsService settingsService;
    private final OpenAiCompatibleClient deepSeekClient;
    private final LocalEmbeddingService embeddingService;
    private final MilvusSchemaStore milvusSchemaStore;

    public SettingsController(SettingsService settingsService,
                              OpenAiCompatibleClient deepSeekClient,
                              LocalEmbeddingService embeddingService,
                              MilvusSchemaStore milvusSchemaStore) {
        this.settingsService = settingsService;
        this.deepSeekClient = deepSeekClient;
        this.embeddingService = embeddingService;
        this.milvusSchemaStore = milvusSchemaStore;
    }

    @GetMapping("/api/settings")
    public Map<String, Object> getSettings() {
        return settingsService.getAllMasked();
    }

    @PostMapping("/api/settings/update")
    public Map<String, Object> update(@RequestBody Map<String, String> body) {
        try {
            boolean embChanged = containsPrefix(body, "embedding.");
            boolean milvusChanged = containsPrefix(body, "milvus.");
            settingsService.updateBatch(body);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("applied", true);

            if (embChanged) {
                embeddingService.reload();
                resp.put("embeddingReloaded", true);
                resp.put("embeddingProvider", embeddingService.provider());
            }
            if (milvusChanged) {
                boolean ok = milvusSchemaStore.reconnect();
                resp.put("milvusReconnected", ok);
                if (!ok) {
                    resp.put("milvusError", "Milvus 重连失败，请检查配置");
                }
            }
            return resp;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "保存失败" : ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/settings/open-data-dir")
    public Map<String, Object> openDataDir() {
        try {
            File dir = SqliteInitializer.DB_PATH.getParent().toFile();
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            } else {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                } else {
                    throw new IllegalStateException("当前环境不支持打开目录");
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("path", dir.getAbsolutePath());
            return body;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/api/ai/llm/test")
    public Map<String, Object> testLlm(@RequestBody(required = false) Map<String, Object> body) {
        long start = System.currentTimeMillis();
        try {
            String provider = formOrSaved(body, "provider", settingsService.llmProvider());
            boolean compatible = "OPENAI_COMPATIBLE".equalsIgnoreCase(provider);
            String baseUrl = formOrSaved(body, "baseUrl", settingsService.llmBaseUrl());
            String model = formOrSaved(body, "model", settingsService.llmModel());
            String apiKey = readTestApiKey(body, compatible);
            deepSeekClient.testConnection(baseUrl, apiKey, model, provider);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("latencyMs", System.currentTimeMillis() - start);
            resp.put("model", model);
            return resp;
        } catch (Exception ex) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("latencyMs", System.currentTimeMillis() - start);
            resp.put("message", ex.getMessage());
            return resp;
        }
    }

    @PostMapping("/api/ai/embedding/test")
    public Map<String, Object> testEmbedding(@RequestBody(required = false) Map<String, Object> body) {
        long start = System.currentTimeMillis();
        try {
            String text = "连接测试";
            if (body != null && body.get("text") != null && StringUtils.hasText(String.valueOf(body.get("text")))) {
                text = String.valueOf(body.get("text")).trim();
            }
            float[] v = embeddingService.embed(text);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("latencyMs", System.currentTimeMillis() - start);
            resp.put("dimension", v.length);
            resp.put("provider", embeddingService.provider());
            return resp;
        } catch (Exception ex) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", false);
            resp.put("latencyMs", System.currentTimeMillis() - start);
            resp.put("message", ex.getMessage());
            resp.put("provider", embeddingService.provider());
            return resp;
        }
    }

    private static boolean containsPrefix(Map<String, String> body, String prefix) {
        if (body == null || body.isEmpty()) {
            return false;
        }
        for (String key : body.keySet()) {
            if (key != null && key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 表单显式带了字段时用表单值（含空串）；没带才回退已保存配置。 */
    private static String formOrSaved(Map<String, Object> body, String key, String fallback) {
        if (body != null && body.containsKey(key) && body.get(key) != null) {
            String s = String.valueOf(body.get(key)).trim();
            return "null".equalsIgnoreCase(s) ? "" : s;
        }
        return fallback;
    }

    /** OpenAI 兼容允许空 Key；DeepSeek 留空时仍用已保存的 Key。 */
    private String readTestApiKey(Map<String, Object> body, boolean compatible) {
        if (body != null && body.containsKey("apiKey") && body.get("apiKey") != null) {
            String s = String.valueOf(body.get("apiKey")).trim();
            if ("null".equalsIgnoreCase(s)) {
                s = "";
            }
            if (StringUtils.hasText(s) || compatible) {
                return s;
            }
        }
        return settingsService.llmApiKey();
    }

    private static String str(Map<String, Object> body, String key, String def) {
        if (body == null || body.get(key) == null) {
            return def;
        }
        String s = String.valueOf(body.get(key)).trim();
        return StringUtils.hasText(s) && !"null".equalsIgnoreCase(s) ? s : def;
    }
}
