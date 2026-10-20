package com.dbsidekick.text2sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Text2SQL 门面。对外方法签名保持不变，流水线委托给 {@link Text2SqlPipeline}。
 */
@Service
public class Text2SqlService {

    private final Text2SqlPipeline pipeline;

    public Text2SqlService(Text2SqlPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public Text2SqlResult query(String datasourceId, String question, int topK) {
        return streamQuery(datasourceId, question, topK, null, null);
    }

    public Text2SqlResult streamQuery(String datasourceId, String question, int topK, StepConsumer consumer) {
        return streamQuery(datasourceId, question, topK, null, consumer);
    }

    public Text2SqlResult streamQuery(String datasourceId, String question, int topK,
                                      String sessionId, StepConsumer consumer) {
        return pipeline.streamQuery(datasourceId, question, topK, sessionId, consumer);
    }

    /**
     * 诊断用：复现 Prompt 组装链路，不调用生成 SQL / 不执行 / 不 markUsed。
     * 与 {@link #streamQuery} 前半段保持同序同入参，便于对照追问场景。
     */
    public Map<String, Object> debugPrompt(String datasourceId, String question, String sessionId, int topK) {
        return pipeline.debugPrompt(datasourceId, question, sessionId, topK);
    }

    /** 原问题 MD5 的前 8 位，用作同一会话内去重。 */
    public static String questionHash(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(question.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.substring(0, 8);
        } catch (Exception ex) {
            return null;
        }
    }

    static String prepareLlmSql(String raw, boolean scriptMode) {
        return LlmSqlNormalizer.prepareLlmSql(raw, scriptMode);
    }

    static String cleanSql(String raw) {
        return LlmSqlNormalizer.cleanSql(raw);
    }

    static String cleanSql(String raw, boolean scriptMode) {
        return LlmSqlNormalizer.cleanSql(raw, scriptMode);
    }
}
