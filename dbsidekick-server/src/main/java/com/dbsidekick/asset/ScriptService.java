package com.dbsidekick.asset;

import com.dbsidekick.config.SqliteInitializer;
import com.dbsidekick.relation.RelationUsageService;
import com.dbsidekick.relation.UsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ScriptService {

    private static final Logger log = LoggerFactory.getLogger(ScriptService.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SqliteInitializer sqliteInitializer;
    private final RelationUsageService relationUsageService;

    public ScriptService(SqliteInitializer sqliteInitializer, RelationUsageService relationUsageService) {
        this.sqliteInitializer = sqliteInitializer;
        this.relationUsageService = relationUsageService;
    }

    public List<ScriptSummary> list(String keyword, Boolean onlyFav) throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, datasource_id, db_name, source, favorited, updated_at
                FROM script
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND name LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        if (Boolean.TRUE.equals(onlyFav)) {
            sql.append(" AND favorited = 1");
        }
        sql.append(" ORDER BY updated_at DESC");

        List<ScriptSummary> list = new ArrayList<>();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScriptSummary s = new ScriptSummary();
                    s.setId(rs.getString("id"));
                    s.setName(rs.getString("name"));
                    s.setDatasourceId(rs.getString("datasource_id"));
                    s.setDbName(rs.getString("db_name"));
                    s.setSource(rs.getString("source"));
                    s.setFavorited(rs.getInt("favorited") == 1);
                    s.setUpdatedAt(rs.getString("updated_at"));
                    list.add(s);
                }
            }
        }
        return list;
    }

    public ScriptDetail get(String id) throws Exception {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id 不能为空");
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT id, name, content, datasource_id, db_name, source, favorited, created_at, updated_at
                     FROM script WHERE id = ?
                     """)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("脚本不存在: " + id);
                }
                return mapDetail(rs);
            }
        }
    }

    public String save(ScriptDetail detail) throws Exception {
        if (detail == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (!StringUtils.hasText(detail.getName())) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (detail.getContent() == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        String source = StringUtils.hasText(detail.getSource()) ? detail.getSource().trim() : "MANUAL";
        String now = TS.format(Instant.now());

        if (StringUtils.hasText(detail.getId()) && exists(detail.getId())) {
            try (Connection conn = sqliteInitializer.open();
                 PreparedStatement ps = conn.prepareStatement("""
                         UPDATE script
                         SET name = ?, content = ?, datasource_id = ?, db_name = ?, source = ?, updated_at = ?
                         WHERE id = ?
                         """)) {
                ps.setString(1, detail.getName().trim());
                ps.setString(2, detail.getContent());
                ps.setString(3, blankToNull(detail.getDatasourceId()));
                ps.setString(4, blankToNull(detail.getDbName()));
                ps.setString(5, source);
                ps.setString(6, now);
                ps.setString(7, detail.getId());
                ps.executeUpdate();
            }
            recordAiScriptUsage(detail);
            return detail.getId();
        }

        String id = StringUtils.hasText(detail.getId()) ? detail.getId().trim() : UUID.randomUUID().toString();
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO script (id, name, content, datasource_id, db_name, source, favorited, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setString(1, id);
            ps.setString(2, detail.getName().trim());
            ps.setString(3, detail.getContent());
            ps.setString(4, blankToNull(detail.getDatasourceId()));
            ps.setString(5, blankToNull(detail.getDbName()));
            ps.setString(6, source);
            ps.setInt(7, detail.isFavorited() ? 1 : 0);
            ps.setString(8, now);
            ps.setString(9, now);
            ps.executeUpdate();
        }
        recordAiScriptUsage(detail);
        return id;
    }

    private void recordAiScriptUsage(ScriptDetail detail) {
        if (detail == null || !StringUtils.hasText(detail.getDatasourceId()) || detail.getContent() == null) {
            return;
        }
        String source = detail.getSource() == null ? "" : detail.getSource().trim();
        if (!"AI_GENERATED".equalsIgnoreCase(source)) {
            return;
        }
        try {
            relationUsageService.recordUsage(
                    detail.getDatasourceId(),
                    null,
                    null,
                    detail.getContent(),
                    new UsageContext(true, -1, true, false));
        } catch (Exception ex) {
            log.warn("[Sidekick][script] relation usage record skipped: {}", ex.getMessage());
        }
    }

    public void delete(String id) throws Exception {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id 不能为空");
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM script WHERE id = ?")) {
            ps.setString(1, id);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new IllegalArgumentException("脚本不存在: " + id);
            }
        }
    }

    public void toggleFavorite(String id) throws Exception {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("id 不能为空");
        }
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("""
                     UPDATE script
                     SET favorited = CASE WHEN favorited = 1 THEN 0 ELSE 1 END,
                         updated_at = ?
                     WHERE id = ?
                     """)) {
            ps.setString(1, TS.format(Instant.now()));
            ps.setString(2, id);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new IllegalArgumentException("脚本不存在: " + id);
            }
        }
    }

    private boolean exists(String id) throws Exception {
        try (Connection conn = sqliteInitializer.open();
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM script WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static ScriptDetail mapDetail(ResultSet rs) throws Exception {
        ScriptDetail d = new ScriptDetail();
        d.setId(rs.getString("id"));
        d.setName(rs.getString("name"));
        d.setContent(rs.getString("content"));
        d.setDatasourceId(rs.getString("datasource_id"));
        d.setDbName(rs.getString("db_name"));
        d.setSource(rs.getString("source"));
        d.setFavorited(rs.getInt("favorited") == 1);
        d.setCreatedAt(rs.getString("created_at"));
        d.setUpdatedAt(rs.getString("updated_at"));
        return d;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
