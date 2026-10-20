package com.dbsidekick.schema;

/**
 * Schema 同步可选行为。
 */
public record SyncOptions(boolean skipAutoInfer, ProgressListener progress) {

    public static SyncOptions defaults() {
        return new SyncOptions(false, ProgressListener.NOOP);
    }

    public static SyncOptions of(boolean skipAutoInfer, ProgressListener progress) {
        return new SyncOptions(skipAutoInfer, ProgressListener.orNoop(progress));
    }

    public ProgressListener progressOrNoop() {
        return ProgressListener.orNoop(progress);
    }
}
