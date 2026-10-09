package com.dbsidekick.asset;

import com.dbsidekick.config.SqliteInitializer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ChatSessionService {

    public static final String DEFAULT_TITLE = "新会话";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SqliteInitializer sqliteInitializer;

    public ChatSessionService(SqliteInitializer sqliteInitializer) {
        this.sqliteInitializer = sqliteInitializer;
    }

    public String createSession(String datasourceId, String dbName) throws Exception {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String id = UUID.randomUUID().toString();
        String now = now();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO chat_session(id, title, datasource_id, db_name, created_at, updated_at)
                     VALUES(?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, id);
            ps.setString(2, DEFAULT_TITLE);
            ps.setString(3, datasourceId.trim());
            ps.setString(4, StringUtils.hasText(dbName) ? dbName.trim() : null);
            ps.setString(5, now);
            ps.setString(6, now);
            ps.executeUpdate();
        }
        return id;
    }

    public void updateTitleIfDefault(String sessionId, String firstQuestion) throws Exception {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(firstQuestion)) {
            return;
        }
        String title = truncateTitle(firstQuestion.trim());
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE chat_session SET title = ?, updated_at = ?
                     WHERE id = ? AND title = ?
                     """)) {
            ps.setString(1, title);
            ps.setString(2, now());
            ps.setString(3, sessionId.trim());
            ps.setString(4, DEFAULT_TITLE);
            ps.executeUpdate();
        }
    }

    public void renameSession(String sessionId, String title) throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("title 不能为空");
        }
        String t = title.trim();
        if (t.length() > 40) {
            t = t.substring(0, 40);
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE chat_session SET title = ?, updated_at = ? WHERE id = ?
                     """)) {
            ps.setString(1, t);
            ps.setString(2, now());
            ps.setString(3, sessionId.trim());
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new IllegalArgumentException("会话不存在: " + sessionId);
            }
        }
    }

    /**
     * 没提问就关掉的会话不保留。只删没有任何消息的行，进行中的提问会和首条消息一起写入。
     */
    public int purgeEmptySessions() throws Exception {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     DELETE FROM chat_session
                     WHERE NOT EXISTS (
                         SELECT 1 FROM chat_message m WHERE m.session_id = chat_session.id
                     )
                     """)) {
            return ps.executeUpdate();
        }
    }

    public List<SessionSummary> listSessions(String keyword) throws Exception {
        purgeEmptySessions();
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.title, s.datasource_id, s.db_name, s.updated_at,
                       (SELECT COUNT(1) FROM chat_message m WHERE m.session_id = s.id) AS message_count
                FROM chat_session s
                WHERE EXISTS (SELECT 1 FROM chat_message m WHERE m.session_id = s.id)
                """);
        List<Object> params = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND s.title LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY s.updated_at DESC");

        List<SessionSummary> list = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SessionSummary s = new SessionSummary();
                    s.setId(rs.getString("id"));
                    s.setTitle(rs.getString("title"));
                    s.setDatasourceId(rs.getString("datasource_id"));
                    s.setDbName(rs.getString("db_name"));
                    s.setUpdatedAt(rs.getString("updated_at"));
                    s.setMessageCount(rs.getInt("message_count"));
                    list.add(s);
                }
            }
        }
        return list;
    }

    public SessionDetail getSession(String sessionId) throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, title, datasource_id, db_name, created_at, updated_at
                     FROM chat_session WHERE id = ?
                     """)) {
            ps.setString(1, sessionId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("会话不存在: " + sessionId);
                }
                SessionDetail detail = new SessionDetail();
                detail.setId(rs.getString("id"));
                detail.setTitle(rs.getString("title"));
                detail.setDatasourceId(rs.getString("datasource_id"));
                detail.setDbName(rs.getString("db_name"));
                detail.setCreatedAt(rs.getString("created_at"));
                detail.setUpdatedAt(rs.getString("updated_at"));
                detail.setMessages(loadMessages(conn, detail.getId(), null));
                return detail;
            }
        }
    }

    public void deleteSession(String sessionId) throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        String id = sessionId.trim();
        try (Connection conn = sqliteInitializer.open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chat_message WHERE session_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM chat_session WHERE id = ?")) {
                    ps.setString(1, id);
                    int n = ps.executeUpdate();
                    if (n == 0) {
                        throw new IllegalArgumentException("会话不存在: " + sessionId);
                    }
                }
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    public void appendMessage(String sessionId, MessageDetail msg) throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (msg == null || !StringUtils.hasText(msg.getRole()) || msg.getContent() == null) {
            throw new IllegalArgumentException("消息不完整");
        }
        String id = StringUtils.hasText(msg.getId()) ? msg.getId() : UUID.randomUUID().toString();
        String now = StringUtils.hasText(msg.getCreatedAt()) ? msg.getCreatedAt() : now();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO chat_message(id, session_id, role, content, sql_text, sql_status,
                       result_json, reasoning_json, created_at)
                     VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, id);
            ps.setString(2, sessionId.trim());
            ps.setString(3, msg.getRole().trim());
            ps.setString(4, msg.getContent());
            ps.setString(5, msg.getSqlText());
            ps.setString(6, msg.getSqlStatus());
            ps.setString(7, msg.getResultJson());
            ps.setString(8, msg.getReasoningJson());
            ps.setString(9, now);
            ps.executeUpdate();
        }
        msg.setId(id);
        msg.setSessionId(sessionId.trim());
        msg.setCreatedAt(now);
    }

    /**
     * 取最近 N 条消息，按 created_at ASC 返回（便于拼对话历史）。
     */
    public List<MessageDetail> recentMessages(String sessionId, int limit) throws Exception {
        if (!StringUtils.hasText(sessionId) || limit <= 0) {
            return List.of();
        }
        try (Connection conn = sqliteInitializer.open()) {
            return loadMessages(conn, sessionId.trim(), limit);
        }
    }

    public void touchSession(String sessionId) throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE chat_session SET updated_at = ? WHERE id = ?
                     """)) {
            ps.setString(1, now());
            ps.setString(2, sessionId.trim());
            ps.executeUpdate();
        }
    }

    public String getTitle(String sessionId) throws Exception {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("SELECT title FROM chat_session WHERE id = ?")) {
            ps.setString(1, sessionId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("title") : null;
            }
        }
    }

    private List<MessageDetail> loadMessages(Connection conn, String sessionId, Integer limit) throws Exception {
        String sql;
        if (limit != null && limit > 0) {
            // SQLite: 先按时间倒序取 limit，再反转成 ASC
            sql = """
                    SELECT id, session_id, role, content, sql_text, sql_status, result_json, reasoning_json, created_at
                    FROM chat_message
                    WHERE session_id = ?
                    ORDER BY created_at DESC, rowid DESC
                    LIMIT ?
                    """;
        } else {
            sql = """
                    SELECT id, session_id, role, content, sql_text, sql_status, result_json, reasoning_json, created_at
                    FROM chat_message
                    WHERE session_id = ?
                    ORDER BY created_at ASC, rowid ASC
                    """;
        }
        List<MessageDetail> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            if (limit != null && limit > 0) {
                ps.setInt(2, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapMessage(rs));
                }
            }
        }
        if (limit != null && limit > 0) {
            Collections.reverse(list);
        }
        return list;
    }

    private static MessageDetail mapMessage(ResultSet rs) throws Exception {
        MessageDetail m = new MessageDetail();
        m.setId(rs.getString("id"));
        m.setSessionId(rs.getString("session_id"));
        m.setRole(rs.getString("role"));
        m.setContent(rs.getString("content"));
        m.setSqlText(rs.getString("sql_text"));
        m.setSqlStatus(rs.getString("sql_status"));
        m.setResultJson(rs.getString("result_json"));
        m.setReasoningJson(rs.getString("reasoning_json"));
        m.setCreatedAt(rs.getString("created_at"));
        return m;
    }

    /**
     * 当前数据源下用户问过的高频短问题，用作空状态示例。
     * datasourceId 为空时返回空列表。
     */
    public List<String> getFrequentQuestions(String datasourceId, int limit) throws Exception {
        if (!StringUtils.hasText(datasourceId)) {
            return List.of();
        }
        int cap = limit <= 0 ? 5 : Math.min(limit, 20);
        int fetch = Math.min(Math.max(cap * 8, cap), 200);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String sql = """
                SELECT content, COUNT(*) AS cnt
                FROM chat_message
                WHERE session_id IN (
                  SELECT id FROM chat_session WHERE datasource_id = ?
                )
                AND role = 'user'
                AND length(content) BETWEEN 5 AND 50
                GROUP BY content
                ORDER BY cnt DESC
                LIMIT ?
                """;
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasourceId.trim());
            ps.setInt(2, fetch);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String content = rs.getString("content");
                    if (!acceptableExample(content)) {
                        continue;
                    }
                    String text = content.trim();
                    if (!seen.add(text)) {
                        continue;
                    }
                    out.add(text);
                    if (out.size() >= cap) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static boolean acceptableExample(String raw) {
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        String s = raw.trim();
        if (s.length() < 5 || s.length() > 50) {
            return false;
        }
        if (s.matches("\\d+")) {
            return false;
        }
        if (s.matches("[\\p{P}\\p{S}\\s]+")) {
            return false;
        }
        String[] followUps = {"重新生成", "再来一次", "改成", "换成", "加上", "去掉", "带上"};
        for (String word : followUps) {
            if (s.contains(word)) {
                return false;
            }
        }
        return true;
    }

    static String truncateTitle(String question) {
        String q = question.replaceAll("\\s+", " ").trim();
        if (q.length() <= 10) {
            return q.isEmpty() ? DEFAULT_TITLE : q;
        }
        return q.substring(0, 10);
    }

    private static String now() {
        return TS.format(Instant.now());
    }
}
