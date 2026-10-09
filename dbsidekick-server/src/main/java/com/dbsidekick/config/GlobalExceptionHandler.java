package com.dbsidekick.config;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 统一异常出口：技术错误 → 用户可读中文提示。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        log.error("[Sidekick] BAD_REQUEST", ex);
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                safeMsg(ex.getMessage(), "请求参数不正确"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex) {
        log.error("[Sidekick] STATUS {}", ex.getStatusCode(), ex);
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = ex.getReason();
        String code = status == HttpStatus.BAD_REQUEST ? "BAD_REQUEST" : "INTERNAL_ERROR";
        return body(status, code, safeMsg(reason, status.getReasonPhrase()));
    }

    @ExceptionHandler({
            ConnectException.class,
            java.net.NoRouteToHostException.class,
            java.net.UnknownHostException.class
    })
    public ResponseEntity<Map<String, Object>> handleDbConnect(Exception ex) {
        log.error("[Sidekick] DB_CONNECT_FAILED", ex);
        return body(HttpStatus.BAD_GATEWAY, "DB_CONNECT_FAILED",
                "无法连接到数据库，请检查连接配置");
    }

    @ExceptionHandler({SQLTimeoutException.class})
    public ResponseEntity<Map<String, Object>> handleSqlTimeout(SQLTimeoutException ex) {
        log.error("[Sidekick] DB_TIMEOUT", ex);
        return body(HttpStatus.GATEWAY_TIMEOUT, "DB_TIMEOUT",
                "数据库响应超时，请稍后重试");
    }

    @ExceptionHandler(SQLSyntaxErrorException.class)
    public ResponseEntity<Map<String, Object>> handleSqlSyntax(SQLSyntaxErrorException ex) {
        log.error("[Sidekick] SQL_SYNTAX_ERROR", ex);
        String detail = simplifySqlMessage(ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "SQL_SYNTAX_ERROR",
                "SQL 语法错误：" + detail);
    }

    @ExceptionHandler(SQLException.class)
    public ResponseEntity<Map<String, Object>> handleSql(SQLException ex) {
        log.error("[Sidekick] SQLException", ex);
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (isConnectFailure(msg) || isConnectCause(ex)) {
            return body(HttpStatus.BAD_GATEWAY, "DB_CONNECT_FAILED",
                    "无法连接到数据库，请检查连接配置");
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return body(HttpStatus.GATEWAY_TIMEOUT, "DB_TIMEOUT",
                    "数据库响应超时，请稍后重试");
        }
        if (msg.contains("syntax") || "42000".equals(ex.getSQLState())) {
            return body(HttpStatus.BAD_REQUEST, "SQL_SYNTAX_ERROR",
                    "SQL 语法错误：" + simplifySqlMessage(ex.getMessage()));
        }
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "操作失败：" + simplifySqlMessage(ex.getMessage()));
    }

    @ExceptionHandler({JsonParseException.class, JsonMappingException.class})
    public ResponseEntity<Map<String, Object>> handleJson(Exception ex) {
        log.error("[Sidekick] JSON_PARSE_ERROR", ex);
        return body(HttpStatus.BAD_REQUEST, "JSON_PARSE_ERROR", "数据格式错误");
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> handleHttpClient(HttpClientErrorException ex) {
        log.error("[Sidekick] LLM HTTP {}", ex.getStatusCode(), ex);
        int code = ex.getStatusCode().value();
        if (code == 401 || code == 403) {
            return body(HttpStatus.UNAUTHORIZED, "LLM_AUTH_FAILED",
                    "AI 服务认证失败，请检查 API Key");
        }
        if (code == 429) {
            return body(HttpStatus.TOO_MANY_REQUESTS, "LLM_RATE_LIMIT",
                    "AI 服务调用频率超限，请稍后重试");
        }
        return body(HttpStatus.BAD_GATEWAY, "LLM_ERROR",
                "AI 服务调用失败，请稍后重试");
    }

    @ExceptionHandler({
            SocketTimeoutException.class,
            TimeoutException.class,
            ResourceAccessException.class
    })
    public ResponseEntity<Map<String, Object>> handleTimeout(Exception ex) {
        log.error("[Sidekick] TIMEOUT", ex);
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (msg.contains("milvus") || isMilvusCause(ex)) {
            return body(HttpStatus.SERVICE_UNAVAILABLE, "MILVUS_UNAVAILABLE",
                    "向量数据库不可用，请检查 Milvus 是否启动");
        }
        if (isDbCause(ex)) {
            return body(HttpStatus.GATEWAY_TIMEOUT, "DB_TIMEOUT",
                    "数据库响应超时，请稍后重试");
        }
        return body(HttpStatus.GATEWAY_TIMEOUT, "LLM_TIMEOUT",
                "AI 服务响应超时，请稍后重试");
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Void> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        // SSE 超时后响应可能已提交，勿再写 JSON，否则二次异常
        log.warn("[Sidekick] async request timeout (often SSE): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleOther(Exception ex) {
        if (ex instanceof AsyncRequestTimeoutException) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        log.error("[Sidekick] INTERNAL_ERROR", ex);
        if (isMilvusException(ex) || isMilvusCause(ex)) {
            return body(HttpStatus.SERVICE_UNAVAILABLE, "MILVUS_UNAVAILABLE",
                    "向量数据库不可用，请检查 Milvus 是否启动");
        }
        if (isConnectCause(ex)) {
            return body(HttpStatus.BAD_GATEWAY, "DB_CONNECT_FAILED",
                    "无法连接到数据库，请检查连接配置");
        }
        String raw = ex.getMessage();
        // DeepSeek 认证失败常包在 IllegalStateException 里
        if (raw != null) {
            String lower = raw.toLowerCase();
            if (lower.contains("401") || lower.contains("unauthorized")
                    || lower.contains("invalid api key") || lower.contains("authentication")) {
                return body(HttpStatus.UNAUTHORIZED, "LLM_AUTH_FAILED",
                        "AI 服务认证失败，请检查 API Key");
            }
            if (lower.contains("429") || lower.contains("rate limit")) {
                return body(HttpStatus.TOO_MANY_REQUESTS, "LLM_RATE_LIMIT",
                        "AI 服务调用频率超限，请稍后重试");
            }
            if (lower.contains("milvus")) {
                return body(HttpStatus.SERVICE_UNAVAILABLE, "MILVUS_UNAVAILABLE",
                        "向量数据库不可用，请检查 Milvus 是否启动");
            }
        }
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "操作失败：" + simplifyGeneric(raw));
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String error) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", false);
        map.put("error", error);
        map.put("code", code);
        return ResponseEntity.status(status).body(map);
    }

    private static String safeMsg(String msg, String fallback) {
        if (msg == null || msg.isBlank()) {
            return fallback;
        }
        return simplifyGeneric(msg);
    }

    private static String simplifySqlMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return "未知错误";
        }
        String s = msg.replaceAll("(?i)password[=:].*?(;|$)", "")
                .replaceAll("(?i)pwd[=:].*?(;|$)", "")
                .trim();
        if (s.length() > 160) {
            s = s.substring(0, 160) + "…";
        }
        return s;
    }

    private static String simplifyGeneric(String msg) {
        if (msg == null || msg.isBlank()) {
            return "未知错误";
        }
        String s = msg;
        // 去掉可能的连接串敏感信息
        s = s.replaceAll("(?i)(password|pwd|api[_-]?key)\\s*[=:]\\s*[^\\s,;]+", "$1=***");
        s = s.replaceAll("jdbc:[^\\s]+", "jdbc://***");
        if (s.length() > 160) {
            s = s.substring(0, 160) + "…";
        }
        return s;
    }

    private static boolean isConnectFailure(String lowerMsg) {
        return lowerMsg.contains("communications link failure")
                || lowerMsg.contains("connection refused")
                || lowerMsg.contains("access denied")
                || lowerMsg.contains("could not connect")
                || lowerMsg.contains("unknown database")
                || lowerMsg.contains("连接") && lowerMsg.contains("失败");
    }

    private static boolean isConnectCause(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof ConnectException
                    || cur instanceof java.net.UnknownHostException
                    || cur instanceof java.net.NoRouteToHostException) {
                return true;
            }
            String m = cur.getMessage();
            if (m != null && isConnectFailure(m.toLowerCase())) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isDbCause(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof SQLException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isMilvusException(Throwable ex) {
        if (ex == null) {
            return false;
        }
        String name = ex.getClass().getName().toLowerCase();
        return name.contains("milvus");
    }

    private static boolean isMilvusCause(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (isMilvusException(cur)) {
                return true;
            }
            String m = cur.getMessage();
            if (m != null && m.toLowerCase().contains("milvus")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
