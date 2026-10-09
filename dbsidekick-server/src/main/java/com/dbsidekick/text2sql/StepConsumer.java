package com.dbsidekick.text2sql;

/**
 * Text2SQL 流水线步骤回调（同步收集 / SSE 推送共用）。
 */
@FunctionalInterface
public interface StepConsumer {

    void onStep(String phase, String content, long elapsedMs);
}
