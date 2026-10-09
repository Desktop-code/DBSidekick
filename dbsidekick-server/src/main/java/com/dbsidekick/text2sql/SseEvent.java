package com.dbsidekick.text2sql;

/**
 * SSE 事件封装（序列化为 JSON 时用 event 名 + data 载荷分开发送）。
 */
public class SseEvent {

    private String event;
    private Object data;

    public SseEvent() {
    }

    public SseEvent(String event, Object data) {
        this.event = event;
        this.data = data;
    }

    public static SseEvent phase(String phase, String content, long elapsedMs) {
        return new SseEvent("phase", java.util.Map.of(
                "phase", phase == null ? "" : phase,
                "content", content == null ? "" : content,
                "elapsedMs", elapsedMs
        ));
    }

    public static SseEvent result(Object payload) {
        return new SseEvent("result", payload);
    }

    public static SseEvent error(String message) {
        return new SseEvent("error", java.util.Map.of("message", message == null ? "" : message));
    }

    public static SseEvent done() {
        return done(null);
    }

    public static SseEvent done(String sessionId) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (sessionId != null && !sessionId.isBlank()) {
            data.put("sessionId", sessionId);
        }
        return new SseEvent("done", data);
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
