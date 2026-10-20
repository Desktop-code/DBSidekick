package com.dbsidekick.text2sql;

import com.dbsidekick.ai.milvus.SchemaHit;
import java.util.List;
import java.util.Set;

/**
 * 一次请求组装完成的 Prompt 与门禁允许表。
 * Schema 卡片、Few-shot 和历史在组装时写入用户 Prompt，这里保留原件供对照。
 * 请求级对象，不是 Spring Bean。
 */
public final class PromptAssembly {

    private final String systemPrompt;
    private final String userPrompt;
    private final String historyText;
    private final List<SchemaHit> hits;
    private final List<FewShotExample> fewShots;
    private final Set<String> allowedTables;

    public PromptAssembly(String systemPrompt, String userPrompt, String historyText,
                          List<SchemaHit> hits, List<FewShotExample> fewShots,
                          Set<String> allowedTables) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.historyText = historyText;
        this.hits = hits;
        this.fewShots = fewShots;
        this.allowedTables = allowedTables;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public String getHistoryText() {
        return historyText;
    }

    public List<SchemaHit> getHits() {
        return hits;
    }

    public List<FewShotExample> getFewShots() {
        return fewShots;
    }

    public Set<String> getAllowedTables() {
        return allowedTables;
    }
}
