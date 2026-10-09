package com.dbsidekick.text2sql;

/**
 * Text2SQL 推理过程中的一步。
 */
public class ReasoningStep {

    private String phase;
    private String content;
    private long elapsedMs;

    public ReasoningStep() {
    }

    public ReasoningStep(String phase, String content, long elapsedMs) {
        this.phase = phase;
        this.content = content;
        this.elapsedMs = elapsedMs;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }
}
