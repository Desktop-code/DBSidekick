package com.dbsidekick.text2sql;

import java.util.List;

/**
 * 单次请求的推理步骤收集器。先写入步骤列表，再回调 {@link StepConsumer}。
 * 请求级对象，不是 Spring Bean。
 */
public final class ReasoningTrace {

    private final List<ReasoningStep> steps;
    private final StepConsumer consumer;

    public ReasoningTrace(List<ReasoningStep> steps, StepConsumer consumer) {
        this.steps = steps;
        this.consumer = consumer;
    }

    /**
     * 记录一步推理。consumer 为空时只收集，不向外推送。
     */
    public void emit(String phase, String content, long elapsedMs) {
        steps.add(new ReasoningStep(phase, content, elapsedMs));
        if (consumer != null) {
            consumer.onStep(phase, content, elapsedMs);
        }
    }

    public List<ReasoningStep> steps() {
        return steps;
    }
}
