package com.dbsidekick.config;

/**
 * 将技术异常转为用户可读提示（供全局处理器与业务 catch 复用）。
 */
public final class UserFacingErrors {

    private UserFacingErrors() {
    }

    public static String of(Throwable ex) {
        if (ex == null) {
            return "操作失败：未知错误";
        }
        String mapped = map(ex);
        return mapped != null ? mapped : ("操作失败：" + simplify(ex.getMessage()));
    }

    public static String ofMessage(String technical) {
        if (technical == null || technical.isBlank()) {
            return "操作失败：未知错误";
        }
        String lower = technical.toLowerCase();
        if (containsAny(lower, "milvus", "vector database")) {
            return "向量数据库不可用，请检查 Milvus 是否启动";
        }
        if (containsAny(lower, "401", "unauthorized", "invalid api key", "authentication", "api key")) {
            return "AI 服务认证失败，请检查 API Key";
        }
        if (containsAny(lower, "429", "rate limit")) {
            return "AI 服务调用频率超限，请稍后重试";
        }
        if (containsAny(lower, "timeout", "timed out", "read timed out")) {
            if (containsAny(lower, "deepseek", "openai", "llm", "api.deepseek")) {
                return "AI 服务响应超时，请稍后重试";
            }
            if (containsAny(lower, "milvus")) {
                return "向量数据库不可用，请检查 Milvus 是否启动";
            }
            return "数据库响应超时，请稍后重试";
        }
        if (isConnectFailure(lower)) {
            return "无法连接到数据库，请检查连接配置";
        }
        if (containsAny(lower, "syntax", "you have an error in your sql")) {
            return "SQL 语法错误：" + simplify(technical);
        }
        if (containsAny(lower, "can't reopen table", "cannot reopen table")) {
            return "MySQL 限制：同一条 SQL 不能两次打开同一张临时表。"
                    + "请复制临时表后再关联，或改写为不重复引用的写法。";
        }
        if (containsAny(lower, "near 'limit", "near \"limit") || technical.contains("near 'LIMIT")
                || containsAny(lower, "limit 100 limit")) {
            return "SQL 语法错误：派生表可能缺少别名，或出现了重复 LIMIT（如 LIMIT 100 LIMIT 100）。"
                    + "请写成 FROM (SELECT ...) AS t LIMIT 100，且整段只保留一个最外层 LIMIT。";
        }
        return "操作失败：" + simplify(technical);
    }

    private static String map(Throwable ex) {
        Throwable cur = ex;
        int depth = 0;
        while (cur != null && depth++ < 10) {
            String name = cur.getClass().getName().toLowerCase();
            if (name.contains("milvus")) {
                return "向量数据库不可用，请检查 Milvus 是否启动";
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lowerMsg = msg.toLowerCase();
                if (containsAny(lowerMsg, "can't reopen table", "cannot reopen table")) {
                    return "MySQL 限制：同一条 SQL 不能两次打开同一张临时表。"
                            + "请复制临时表后再关联，或改写为不重复引用的写法。";
                }
                if (containsAny(lowerMsg, "near 'limit", "near \"limit") || msg.contains("near 'LIMIT")
                        || containsAny(lowerMsg, "limit 100 limit")) {
                    return "SQL 语法错误：派生表可能缺少别名，或出现了重复 LIMIT。"
                            + "请写成 FROM (SELECT ...) AS t LIMIT 100，且整段只保留一个最外层 LIMIT。";
                }
            }
            if (cur instanceof java.sql.SQLSyntaxErrorException) {
                return "SQL 语法错误：" + simplify(cur.getMessage());
            }
            if (cur instanceof java.sql.SQLTimeoutException) {
                return "数据库响应超时，请稍后重试";
            }
            if (cur instanceof java.net.ConnectException
                    || cur instanceof java.net.UnknownHostException
                    || cur instanceof java.net.NoRouteToHostException) {
                return "无法连接到数据库，请检查连接配置";
            }
            if (cur instanceof org.springframework.web.client.HttpClientErrorException http) {
                int code = http.getStatusCode().value();
                if (code == 401 || code == 403) {
                    return "AI 服务认证失败，请检查 API Key";
                }
                if (code == 429) {
                    return "AI 服务调用频率超限，请稍后重试";
                }
            }
            if (cur instanceof java.net.SocketTimeoutException
                    || cur instanceof java.util.concurrent.TimeoutException) {
                String m = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase();
                if (m.contains("milvus") || name.contains("milvus")) {
                    return "向量数据库不可用，请检查 Milvus 是否启动";
                }
                return "AI 服务响应超时，请稍后重试";
            }
            if (msg != null) {
                String mapped = ofMessage(msg);
                // ofMessage 总会返回非空；仅当命中特化规则时采用（避免过早 INTERNAL）
                if (!mapped.startsWith("操作失败：")) {
                    return mapped;
                }
                String lower = msg.toLowerCase();
                if (isConnectFailure(lower) || containsAny(lower, "milvus", "401", "unauthorized", "rate limit", "syntax")
                        || containsAny(lower, "can't reopen table", "cannot reopen table")) {
                    return mapped;
                }
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static boolean isConnectFailure(String lowerMsg) {
        return containsAny(lowerMsg,
                "communications link failure",
                "connection refused",
                "access denied",
                "could not connect",
                "unknown database",
                "public key retrieval",
                "using password: yes");
    }

    private static boolean containsAny(String hay, String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String simplify(String msg) {
        if (msg == null || msg.isBlank()) {
            return "未知错误";
        }
        String s = msg.replaceAll("(?i)(password|pwd|api[_-]?key)\\s*[=:]\\s*[^\\s,;]+", "$1=***");
        s = s.replaceAll("jdbc:[^\\s]+", "jdbc://***");
        if (s.length() > 160) {
            s = s.substring(0, 160) + "…";
        }
        return s;
    }
}
