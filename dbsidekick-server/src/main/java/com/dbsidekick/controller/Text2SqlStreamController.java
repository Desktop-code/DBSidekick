package com.dbsidekick.controller;

import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.asset.ChatSessionService;
import com.dbsidekick.asset.MessageDetail;
import com.dbsidekick.asset.SessionDetail;
import com.dbsidekick.datasource.ActiveDatabase;
import com.dbsidekick.schema.SchemaSyncService;
import com.dbsidekick.text2sql.ReasoningStep;
import com.dbsidekick.text2sql.SseEvent;
import com.dbsidekick.text2sql.Text2SqlResult;
import com.dbsidekick.text2sql.Text2SqlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Text2SQL SSE 流式接口（同步 {@code /api/text2sql/query} 保持不变）。
 */
@RestController
@RequestMapping("/api/text2sql")
public class Text2SqlStreamController {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlStreamController.class);
    private static final long SSE_TIMEOUT_MS = 600_000L;
    private static final int RESULT_SNAPSHOT_ROWS = 100;

    private final Text2SqlService text2SqlService;
    private final OpenAiCompatibleClient deepSeekClient;
    private final ChatSessionService chatSessionService;
    private final SchemaSyncService schemaSyncService;
    private final ObjectMapper objectMapper;
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "sidekick-sse");
        t.setDaemon(true);
        return t;
    });

    public Text2SqlStreamController(Text2SqlService text2SqlService,
                                    OpenAiCompatibleClient deepSeekClient,
                                    ChatSessionService chatSessionService,
                                    SchemaSyncService schemaSyncService,
                                    ObjectMapper objectMapper) {
        this.text2SqlService = text2SqlService;
        this.deepSeekClient = deepSeekClient;
        this.chatSessionService = chatSessionService;
        this.schemaSyncService = schemaSyncService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        sseExecutor.shutdownNow();
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String datasourceId = stringVal(body.get("datasourceId"));
        String question = stringVal(body.get("question"));
        String sessionId = stringVal(body.get("sessionId"));
        String dbName = stringVal(body.get("dbName"));
        String preferredTitle = stringVal(body.get("title"));
        int topK = 5;
        if (body.get("topK") instanceof Number n) {
            topK = n.intValue();
        }
        if (!deepSeekClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先配置 DEEPSEEK_API_KEY");
        }
        if (!StringUtils.hasText(datasourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasourceId 不能为空");
        }
        if (!StringUtils.hasText(question)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        BoundScope scope = bindScope(datasourceId, sessionId, dbName);

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");
        // 尽量减小缓冲，利于 SSE 立刻刷出
        try {
            response.setBufferSize(512);
        } catch (Exception ignored) {
            // ignore
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean stopped = new AtomicBoolean(false);
        final long streamStartMs = System.currentTimeMillis();
        log.info("[SSE] stream started ts={}", streamStartMs);
        emitter.onTimeout(() -> {
            stopped.set(true);
            log.warn("[Sidekick][SSE] timeout");
        });
        emitter.onCompletion(() -> stopped.set(true));
        emitter.onError(ex -> {
            stopped.set(true);
            log.warn("[Sidekick][SSE] emitter error: {}", ex.getMessage());
        });

        final String ds = scope.datasourceId();
        final String q = question;
        final int k = topK;
        final String sid = sessionId;
        final String db = scope.dbName();
        final String title = preferredTitle;
        sseExecutor.execute(() -> runStream(emitter, stopped, ds, q, k, sid, db, title, streamStartMs));
        return emitter;
    }

    /**
     * 已有会话以库里记下的数据源和库名为准，客户端传来的不一致值直接丢掉。
     * 老会话没写库名时，用该实例的第一个库并补写回去。
     */
    private BoundScope bindScope(String datasourceId, String sessionId, String dbName) {
        try {
            String ds = datasourceId.trim();
            String db = StringUtils.hasText(dbName) ? dbName.trim() : null;
            if (StringUtils.hasText(sessionId)) {
                SessionDetail detail = chatSessionService.getSession(sessionId.trim());
                if (StringUtils.hasText(detail.getDatasourceId())) {
                    ds = detail.getDatasourceId().trim();
                }
                if (StringUtils.hasText(detail.getDbName())) {
                    String bound = detail.getDbName().trim();
                    if (db != null && !bound.equals(db)) {
                        log.info("[SSE] 会话已绑定库 {}，忽略请求里的 {}", bound, db);
                    }
                    db = bound;
                } else {
                    db = firstDatabase(ds);
                    chatSessionService.assignDbNameIfBlank(sessionId.trim(), db);
                }
            } else if (!StringUtils.hasText(db)) {
                db = firstDatabase(ds);
            }
            if (!StringUtils.hasText(db)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先选择数据库");
            }
            return new BoundScope(ds, db);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ex.getMessage() == null ? "无法确定会话所属数据库" : ex.getMessage());
        }
    }

    private String firstDatabase(String datasourceId) throws Exception {
        List<String> names = schemaSyncService.listDatabases(datasourceId);
        if (names == null || names.isEmpty()) {
            return null;
        }
        return names.get(0);
    }

    private record BoundScope(String datasourceId, String dbName) {
    }

    private void runStream(SseEmitter emitter, AtomicBoolean stopped,
                           String datasourceId, String question, int topK, String sessionId,
                           String dbName, String preferredTitle, long streamStartMs) {
        ActiveDatabase.set(dbName);
        try {
            Text2SqlResult result = text2SqlService.streamQuery(datasourceId, question, topK, sessionId,
                    (phase, content, elapsedMs) -> {
                        if (stopped.get()) {
                            throw new IllegalStateException("SSE 已断开");
                        }
                        send(emitter, stopped, SseEvent.phase(phase, content, elapsedMs), streamStartMs);
                    });

            String savedId = persistTurn(sessionId, datasourceId, dbName, preferredTitle, question, result);

            if (stopped.get()) {
                return;
            }
            if (result.isSuccess()) {
                send(emitter, stopped, SseEvent.result(result.toResultPayload()), streamStartMs);
            } else {
                String msg = StringUtils.hasText(result.getError()) ? result.getError() : "查询失败";
                send(emitter, stopped, SseEvent.error(msg), streamStartMs);
            }
            send(emitter, stopped, SseEvent.done(savedId), streamStartMs);
            emitter.complete();
            log.info("[Sidekick][SSE] done success={} elapsedMs={}", result.isSuccess(), result.getElapsedMs());
        } catch (IllegalStateException disconnected) {
            log.warn("[Sidekick][SSE] client disconnected");
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // ignore
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][SSE] failed: {}", ex.getMessage());
            try {
                if (!stopped.get()) {
                    send(emitter, stopped, SseEvent.error(ex.getMessage() == null ? "流式处理失败" : ex.getMessage()), streamStartMs);
                    send(emitter, stopped, SseEvent.done(), streamStartMs);
                }
                emitter.complete();
            } catch (Exception ignored) {
                try {
                    emitter.completeWithError(ex);
                } catch (Exception ignored2) {
                    // ignore
                }
            }
        } finally {
            ActiveDatabase.clear();
        }
    }

    private String persistTurn(String sessionId, String datasourceId, String dbName, String preferredTitle,
                                String question, Text2SqlResult result) {
        try {
            String sid = sessionId;
            boolean created = false;
            if (!StringUtils.hasText(sid)) {
                sid = chatSessionService.createSession(datasourceId, dbName);
                created = true;
            }
            MessageDetail user = new MessageDetail();
            user.setRole("user");
            user.setContent(question);
            chatSessionService.appendMessage(sid, user);
            if (created && StringUtils.hasText(preferredTitle)
                    && !ChatSessionService.DEFAULT_TITLE.equals(preferredTitle.trim())) {
                chatSessionService.renameSession(sid, preferredTitle.trim());
            } else {
                chatSessionService.updateTitleIfDefault(sid, question);
            }

            String sqlStatus;
            String content;
            if (result.isSuccess()) {
                sqlStatus = "success";
                content = "已生成 SQL，返回 " + result.getRowCount() + " 行";
            } else if (isBlocked(result)) {
                sqlStatus = "blocked";
                content = "已拦截：" + (StringUtils.hasText(result.getError()) ? result.getError() : "安全校验未通过");
            } else {
                sqlStatus = "failed";
                content = "生成失败：" + (StringUtils.hasText(result.getError()) ? result.getError() : "未知错误");
            }

            MessageDetail assistant = new MessageDetail();
            assistant.setRole("assistant");
            assistant.setContent(content);
            assistant.setSqlText(StringUtils.hasText(result.getSql()) ? result.getSql() : result.getRawSql());
            assistant.setSqlStatus(sqlStatus);
            assistant.setResultJson(toResultJson(result));
            assistant.setReasoningJson(toReasoningJson(result.getReasoningSteps()));
            chatSessionService.appendMessage(sid, assistant);
            chatSessionService.touchSession(sid);
            return sid;
        } catch (Exception ex) {
            log.warn("[Sidekick][SSE] persist turn failed: {}", ex.getMessage());
            return StringUtils.hasText(sessionId) ? sessionId : null;
        }
    }

    private String toResultJson(Text2SqlResult result) {
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("success", result.isSuccess());
            snap.put("sql", result.getSql());
            snap.put("rawSql", result.getRawSql());
            snap.put("columns", result.getColumns());
            List<Map<String, Object>> rows = result.getRows() == null ? List.of() : result.getRows();
            if (rows.size() > RESULT_SNAPSHOT_ROWS) {
                rows = new ArrayList<>(rows.subList(0, RESULT_SNAPSHOT_ROWS));
            }
            snap.put("rows", rows);
            snap.put("rowCount", result.getRowCount());
            snap.put("truncated", result.isTruncated());
            snap.put("elapsedMs", result.getElapsedMs());
            snap.put("error", result.getError());
            return objectMapper.writeValueAsString(snap);
        } catch (Exception ex) {
            return null;
        }
    }

    private String toReasoningJson(List<ReasoningStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps == null ? List.of() : steps);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isBlocked(Text2SqlResult result) {
        String err = result.getError();
        if (StringUtils.hasText(err) && (err.contains("只允许") || err.contains("禁止") || err.contains("危险"))) {
            return true;
        }
        if (result.getReasoningSteps() != null) {
            for (ReasoningStep step : result.getReasoningSteps()) {
                if (step == null) {
                    continue;
                }
                String phase = step.getPhase();
                String content = step.getContent();
                if ("安全校验".equals(phase) && content != null
                        && (content.contains("不通过") || content.contains("危险"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void send(SseEmitter emitter, AtomicBoolean stopped, SseEvent event, long streamStartMs) {
        if (stopped.get() || event == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - streamStartMs;
        log.info("[SSE] sending event: {}, elapsed since start: {}ms", event.getEvent(), elapsed);
        try {
            Object data = event.getData();
            if (data == null) {
                data = new LinkedHashMap<>();
            }
            // TEXT_PLAIN 避免再包一层 JSON 引号；Jackson Map 仍会序列化为 JSON 对象字符串
            emitter.send(SseEmitter.event()
                    .name(event.getEvent())
                    .data(data, MediaType.APPLICATION_JSON));
            // 空注释行：促使部分代理/缓冲层尽快刷出上一帧
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException ex) {
            stopped.set(true);
            log.warn("[Sidekick][SSE] client disconnected");
            throw new IllegalStateException("SSE 发送失败", ex);
        } catch (Exception ex) {
            stopped.set(true);
            throw new IllegalStateException("SSE 发送失败: " + ex.getMessage(), ex);
        }
    }

    private static String stringVal(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return StringUtils.hasText(s) && !"null".equalsIgnoreCase(s) ? s : null;
    }
}
