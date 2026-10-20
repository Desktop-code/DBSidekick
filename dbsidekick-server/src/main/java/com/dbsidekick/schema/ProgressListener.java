package com.dbsidekick.schema;

/**
 * 长任务细粒度进度回调（如同步/索引 12/88 张表）。
 * 传入 {@code null} 或 {@link #NOOP} 时与旧行为一致。
 */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NOOP = (step, current, total, message) -> {
    };

    /**
     * @param step    步骤标识，如 SYNC / INFER / ALIAS / INDEX
     * @param current 已完成数量（从 1 计；可为 0 表示尚未开始）
     * @param total   总量；未知时传 0
     * @param message 可选中文说明
     */
    void onProgress(String step, int current, int total, String message);

    static ProgressListener orNoop(ProgressListener listener) {
        return listener == null ? NOOP : listener;
    }
}
