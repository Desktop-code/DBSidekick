package com.dbsidekick.ai.llm;

import com.dbsidekick.ai.SidekickAiProperties;
import com.dbsidekick.config.SettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

/**
 * OpenAI Chat Completions 兼容客户端（DeepSeek / 其他兼容服务 / 本地 Ollama）。
 * 配置优先读 SettingsService（SQLite），回退 application.yml / 环境变量。
 * <p>
 * DeepSeek V4 / reasoner 仍附带 thinking；其他提供商只发标准字段。
 */
@Component
public class OpenAiCompatibleClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleClient.class);
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";
    private static final int DEFAULT_TIMEOUT_SEC = 300;

    private final SidekickAiProperties aiProperties;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public OpenAiCompatibleClient(SidekickAiProperties aiProperties,
                                  @Lazy SettingsService settingsService,
                                  ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
        this.restTemplate = buildRestTemplate(resolveDefaultTimeoutSec());
    }

    public boolean isConfigured() {
        if (compatibleProvider()) {
            return StringUtils.hasText(settingsService.llmBaseUrl())
                    && StringUtils.hasText(settingsService.llmModel());
        }
        return StringUtils.hasText(apiKey());
    }

    public String model() {
        return normalizeModel(settingsService.llmModel());
    }

    public String chat(String systemPrompt, String userPrompt) {
        requireConfigured();
        return doChat(settingsService.llmBaseUrl(), apiKey(), model(),
                settingsService.llmTemperature(), systemPrompt, userPrompt, restTemplate,
                deepseekExtras(settingsService.llmProvider(), model()));
    }

    /**
     * 带读超时的调用（秒）。用于 Query Rewrite 等短超时场景。
     */
    public String chatWithTimeout(String systemPrompt, String userPrompt, int timeoutSeconds) {
        return chatWithTimeout(systemPrompt, userPrompt, timeoutSeconds, null);
    }

    public String chatWithTimeout(String systemPrompt, String userPrompt, int timeoutSeconds, String modelOverride) {
        requireConfigured();
        int sec = Math.max(1, timeoutSeconds);
        RestTemplate shortRt = buildRestTemplate(sec);
        String useModel = StringUtils.hasText(modelOverride)
                ? normalizeModel(modelOverride)
                : model();
        return doChat(settingsService.llmBaseUrl(), apiKey(), useModel,
                settingsService.llmTemperature(), systemPrompt, userPrompt, shortRt,
                deepseekExtras(settingsService.llmProvider(), useModel));
    }

    /** 指定模型的普通调用（如别名生成）。 */
    public String chatWithModel(String systemPrompt, String userPrompt, String modelOverride) {
        requireConfigured();
        String useModel = StringUtils.hasText(modelOverride)
                ? normalizeModel(modelOverride)
                : model();
        return doChat(settingsService.llmBaseUrl(), apiKey(), useModel,
                settingsService.llmTemperature(), systemPrompt, userPrompt, restTemplate,
                deepseekExtras(settingsService.llmProvider(), useModel));
    }

    /** 用指定参数测连（不持久化）。provider 为空时按已保存的提供商。 */
    public void testConnection(String baseUrl, String apiKey, String model, String provider) {
        String useProvider = StringUtils.hasText(provider) ? provider.trim() : settingsService.llmProvider();
        boolean compatible = isCompatible(useProvider);
        if (!compatible && !StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("API Key 不能为空");
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("API 地址不能为空");
        }
        if (!StringUtils.hasText(model)) {
            throw new IllegalStateException("请填写模型名");
        }
        String useModel = normalizeModel(model);
        doChat(baseUrl, apiKey, useModel, 0.1, "You are a ping bot.", "Reply with OK only.", restTemplate,
                deepseekExtras(useProvider, useModel));
    }

    private void requireConfigured() {
        if (isConfigured()) {
            return;
        }
        if (compatibleProvider()) {
            throw new IllegalStateException("请先在设置页填写 API 地址和模型");
        }
        throw new IllegalStateException("请先配置 DEEPSEEK_API_KEY 或在设置页填写 API Key");
    }

    private String doChat(String baseUrl, String apiKey, String model, double temperature,
                          String systemPrompt, String userPrompt, RestTemplate client,
                          boolean deepseekExtras) {
        long start = System.currentTimeMillis();
        String url = completionsUrl(baseUrl);
        String useModel = StringUtils.hasText(model) ? model.trim() : DEFAULT_MODEL;

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", useModel);
        body.put("temperature", temperature);
        body.put("stream", false);
        // 仅 DeepSeek V4 / reasoner 需要；标准 OpenAI 与 Ollama 不接受这两个字段
        if (deepseekExtras) {
            ObjectNode thinking = body.putObject("thinking");
            thinking.put("type", "enabled");
            body.put("reasoning_effort", "low");
        }
        ArrayNode messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt == null ? "" : systemPrompt);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userPrompt == null ? "" : userPrompt);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(apiKey)) {
            headers.setBearerAuth(apiKey.trim());
        }
        headers.setAcceptCharset(java.util.List.of(StandardCharsets.UTF_8));

        try {
            ResponseEntity<String> resp = client.postForEntity(
                    url, new HttpEntity<>(body.toString(), headers), String.class);
            String respBody = resp.getBody();
            if (!StringUtils.hasText(respBody)) {
                throw new IllegalStateException("LLM 返回空响应");
            }
            JsonNode root = objectMapper.readTree(respBody);
            if (root.has("error")) {
                String msg = root.path("error").path("message").asText(root.path("error").toString());
                throw new IllegalStateException("LLM 错误: " + msg);
            }
            JsonNode message = root.path("choices").path(0).path("message");
            String content = extractAssistantText(message);
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("LLM 响应缺少可用 content/reasoning_content");
            }
            long elapsed = System.currentTimeMillis() - start;
            log.info("[Sidekick][LLM] model={} elapsedMs={}", useModel, elapsed);
            return content;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("调用 LLM 失败: " + ex.getMessage(), ex);
        }
    }

    /** 优先 content；thinking 模式下回退 reasoning_content。 */
    static String extractAssistantText(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return "";
        }
        String content = textOrEmpty(message.get("content"));
        if (StringUtils.hasText(content)) {
            return content;
        }
        return textOrEmpty(message.get("reasoning_content"));
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                if (part == null) {
                    continue;
                }
                if (part.isTextual()) {
                    sb.append(part.asText());
                } else if (part.has("text")) {
                    sb.append(part.path("text").asText(""));
                }
            }
            return sb.toString();
        }
        return node.asText("");
    }

    /** 统一 flash 别名到官方 id；未知模型名原样返回。 */
    static String normalizeModel(String raw) {
        if (!StringUtils.hasText(raw)) {
            return DEFAULT_MODEL;
        }
        String m = raw.trim();
        if ("deepseek-v4-flash".equalsIgnoreCase(m)
                || "deepseek-v4-flash-vision-exp".equalsIgnoreCase(m)
                || "flash".equalsIgnoreCase(m)) {
            return "deepseek-flash";
        }
        if ("pro".equalsIgnoreCase(m) || "deepseek-pro".equalsIgnoreCase(m)) {
            return "deepseek-v4-pro";
        }
        return m;
    }

    private boolean compatibleProvider() {
        return isCompatible(settingsService.llmProvider());
    }

    private static boolean isCompatible(String provider) {
        return "OPENAI_COMPATIBLE".equalsIgnoreCase(provider == null ? "" : provider.trim());
    }

    /** DeepSeek 的 V4 / reasoner 才带 thinking；deepseek-chat 与其他服务走标准字段。 */
    private static boolean deepseekExtras(String provider, String model) {
        if (isCompatible(provider) || !StringUtils.hasText(model)) {
            return false;
        }
        String m = model.trim().toLowerCase();
        return m.contains("v4") || m.contains("reasoner");
    }

    private String apiKey() {
        return settingsService.llmApiKey();
    }

    private int resolveDefaultTimeoutSec() {
        int fromYml = ymlLlm().getTimeoutSeconds();
        return fromYml > 0 ? fromYml : DEFAULT_TIMEOUT_SEC;
    }

    private static RestTemplate buildRestTemplate(int timeoutSec) {
        int sec = Math.max(10, timeoutSec);
        return new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(sec))
                .build();
    }

    private SidekickAiProperties.Llm ymlLlm() {
        return aiProperties.getLlm() == null ? new SidekickAiProperties.Llm() : aiProperties.getLlm();
    }

    private static String completionsUrl(String base) {
        String s = trimSlash(base);
        if (s.endsWith("/chat/completions")) {
            return s;
        }
        String lower = s.toLowerCase();
        if ((lower.contains("://api.openai.com") || lower.endsWith("://api.openai.com"))
                && !lower.endsWith("/v1") && !lower.contains("/v1/")) {
            s = s + "/v1";
        }
        return s + "/chat/completions";
    }

    private static String trimSlash(String base) {
        if (!StringUtils.hasText(base)) {
            return "https://api.deepseek.com";
        }
        String s = base.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
