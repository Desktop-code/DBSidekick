package com.dbsidekick.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * 一键初始化 SSE 接口。原有单步 /sync /infer-relations /aliases /index 保持不变。
 */
@RestController
@RequestMapping("/api/schema")
public class SchemaInitController {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitController.class);
    /** 别名生成可达数分钟，实例级更久。 */
    private static final long SSE_TIMEOUT_MS = 1_800_000L;

    private final SchemaInitFacade schemaInitFacade;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, AtomicBoolean> runningByDs = new ConcurrentHashMap<>();
    private final ExecutorService sseExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "schema-init-sse");
        t.setDaemon(true);
        return t;
    });

    public SchemaInitController(SchemaInitFacade schemaInitFacade, ObjectMapper objectMapper) {
        this.schemaInitFacade = schemaInitFacade;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    public void shutdown() {
        sseExecutor.shutdownNow();
    }

    /**
     * 一键初始化（SSE）。
     * <pre>
     * POST /api/schema/init/stream
     * { "datasourceId", "database?", "overwriteAliases?" }
     * </pre>
     */
    @PostMapping(value = "/init/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter initStream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String datasourceId = stringVal(body.get("datasourceId"));
        String database = stringVal(body.get("database"));
        boolean overwriteAliases = Boolean.TRUE.equals(body.get("overwriteAliases"));
        if (!StringUtils.hasText(datasourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasourceId 不能为空");
        }

        AtomicBoolean marker = new AtomicBoolean(true);
        while (true) {
            AtomicBoolean existing = runningByDs.putIfAbsent(datasourceId, marker);
            if (existing == null) {
                break;
            }
            if (existing.get()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该数据源已有初始化任务进行中");
            }
            if (runningByDs.replace(datasourceId, existing, marker)) {
                break;
            }
        }

        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Connection", "keep-alive");
        response.setContentType("text/event-stream;charset=UTF-8");
        try {
            response.setBufferSize(512);
        } catch (Exception ignored) {
            // ignore
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean stopped = new AtomicBoolean(false);
        Runnable release = () -> {
            marker.set(false);
            runningByDs.remove(datasourceId, marker);
        };
        emitter.onTimeout(() -> {
            stopped.set(true);
            release.run();
            log.warn("[Sidekick][init-sse] timeout datasource={}", datasourceId);
        });
        emitter.onCompletion(() -> {
            stopped.set(true);
            release.run();
        });
        emitter.onError(ex -> {
            stopped.set(true);
            release.run();
            log.warn("[Sidekick][init-sse] emitter error: {}", ex.getMessage());
        });

        final String ds = datasourceId;
        final String db = database;
        final boolean overwrite = overwriteAliases;
        sseExecutor.execute(() -> {
            try {
                // INFER∥ALIAS 并行时可能并发回调，SseEmitter 需串行发送
                final Object sendLock = new Object();
                schemaInitFacade.run(ds, db, overwrite, event -> {
                    synchronized (sendLock) {
                        send(emitter, stopped, event);
                    }
                }, stopped);
            } catch (Exception ex) {
                log.warn("[Sidekick][init-sse] run error: {}", ex.getMessage());
                try {
                    send(emitter, stopped, SchemaInitSseEvent.error(
                            ex.getMessage() == null ? "初始化失败" : ex.getMessage()));
                    send(emitter, stopped, SchemaInitSseEvent.done());
                } catch (Exception ignored) {
                    // ignore
                }
            } finally {
                release.run();
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        });
        return emitter;
    }

    private void send(SseEmitter emitter, AtomicBoolean stopped, SchemaInitSseEvent event) {
        if (stopped.get() || event == null) {
            return;
        }
        try {
            Object data = event.getData();
            if (data == null) {
                data = Map.of();
            }
            // 触发序列化校验，避免发送不可序列化对象
            objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event()
                    .name(event.getEvent())
                    .data(data, MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException ex) {
            stopped.set(true);
            log.warn("[Sidekick][init-sse] client disconnected");
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
