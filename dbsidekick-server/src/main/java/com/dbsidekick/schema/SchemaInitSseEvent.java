package com.dbsidekick.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键初始化专用 SSE 事件（与 text2sql {@code SseEvent} 解耦）。
 */
public final class SchemaInitSseEvent {

    private final String event;
    private final Object data;

    private SchemaInitSseEvent(String event, Object data) {
        this.event = event;
        this.data = data;
    }

    public String getEvent() {
        return event;
    }

    public Object getData() {
        return data;
    }

    public static SchemaInitSseEvent initStart(String taskId, String datasourceId, String database,
                                              List<String> databases, List<String> steps) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("datasourceId", datasourceId);
        data.put("database", database);
        data.put("databases", databases);
        data.put("steps", steps);
        return new SchemaInitSseEvent("init_start", data);
    }

    public static SchemaInitSseEvent dbStart(String database, int index, int total) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", database);
        data.put("index", index);
        data.put("total", total);
        data.put("message", "正在初始化库「" + database + "」（" + index + "/" + total + "）");
        return new SchemaInitSseEvent("db_start", data);
    }

    public static SchemaInitSseEvent dbDone(String database, boolean success, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", database);
        data.put("success", success);
        data.put("message", message == null ? "" : message);
        return new SchemaInitSseEvent("db_done", data);
    }

    public static SchemaInitSseEvent step(String database, SchemaInitStep step, String status,
                                         String message, Integer current, Integer total, Object result,
                                         String error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", database);
        data.put("step", step.name());
        data.put("status", status);
        data.put("message", message == null ? "" : message);
        if (current != null) {
            data.put("current", current);
        }
        if (total != null) {
            data.put("total", total);
        }
        if (result != null) {
            data.put("result", result);
        }
        if (error != null) {
            data.put("error", error);
        }
        return new SchemaInitSseEvent("step", data);
    }

    public static SchemaInitSseEvent progress(String database, SchemaInitStep step,
                                              int current, int total, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", database);
        data.put("step", step.name());
        data.put("current", current);
        data.put("total", total);
        data.put("message", message == null ? "" : message);
        return new SchemaInitSseEvent("progress", data);
    }

    public static SchemaInitSseEvent initDone(String overall, long elapsedMs, Object summary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overall", overall);
        data.put("elapsedMs", elapsedMs);
        if (summary != null) {
            data.put("summary", summary);
        }
        return new SchemaInitSseEvent("init_done", data);
    }

    public static SchemaInitSseEvent error(String message) {
        return new SchemaInitSseEvent("error", Map.of("message", message == null ? "" : message));
    }

    public static SchemaInitSseEvent done() {
        return new SchemaInitSseEvent("done", Map.of());
    }
}
