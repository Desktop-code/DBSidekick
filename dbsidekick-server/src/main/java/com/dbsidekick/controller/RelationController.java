package com.dbsidekick.controller;

import com.dbsidekick.asset.ChatSessionService;
import com.dbsidekick.asset.MessageDetail;
import com.dbsidekick.asset.SessionDetail;
import com.dbsidekick.relation.RelationConfigService;
import com.dbsidekick.relation.RelationUsage;
import com.dbsidekick.relation.RelationUsageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/relation")
public class RelationController {

    private final RelationUsageService relationUsageService;
    private final RelationConfigService relationConfigService;
    private final ChatSessionService chatSessionService;

    public RelationController(RelationUsageService relationUsageService,
                              RelationConfigService relationConfigService,
                              ChatSessionService chatSessionService) {
        this.relationUsageService = relationUsageService;
        this.relationConfigService = relationConfigService;
        this.chatSessionService = chatSessionService;
    }

    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam("datasourceId") String datasourceId,
            @RequestParam(value = "database", required = false) String database,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String db = StringUtils.hasText(database) ? database.trim() : null;
        String st = StringUtils.hasText(status) ? status.trim() : null;
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<RelationUsage> rows = relationUsageService.list(datasourceId, db, st, kw, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RelationUsage row : rows) {
            items.add(toItem(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", relationUsageService.count(datasourceId, db, st, kw));
        body.put("items", items);
        return body;
    }

    /**
     * ER 图：节点 + 边。database 可选；status 默认不过滤（由前端传 CONFIRMED 等）。
     */
    @GetMapping("/graph")
    public Map<String, Object> graph(
            @RequestParam("datasourceId") String datasourceId,
            @RequestParam(value = "database", required = false) String database,
            @RequestParam(value = "status", required = false) String status) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String db = StringUtils.hasText(database) ? database.trim() : null;
        String st = StringUtils.hasText(status) ? status.trim() : null;
        return relationUsageService.graph(datasourceId, db, st);
    }

    @PutMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable("id") String id) {
        relationUsageService.markConfirmed(id);
        return ok();
    }

    @PutMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable("id") String id) {
        relationUsageService.markRejected(id);
        return ok();
    }

    @PutMapping("/{id}/reset")
    public Map<String, Object> reset(@PathVariable("id") String id) {
        relationUsageService.resetToPending(id);
        return ok();
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        relationUsageService.delete(id);
        return ok();
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return relationConfigService.all();
    }

    @PostMapping("/config/update")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        relationConfigService.update(body);
        return ok();
    }

    /**
     * 结果区反馈。入参：sessionId、messageId、sql、feedback=USEFUL|USELESS。
     */
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String sessionId = str(body.get("sessionId"));
        String messageId = str(body.get("messageId"));
        String sql = str(body.get("sql"));
        String feedback = str(body.get("feedback")).toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(messageId)) {
            throw new IllegalArgumentException("sessionId 与 messageId 不能为空");
        }
        if (!"USEFUL".equals(feedback) && !"USELESS".equals(feedback)) {
            throw new IllegalArgumentException("feedback 只能是 USEFUL 或 USELESS");
        }
        try {
            SessionDetail session = chatSessionService.getSession(sessionId);
            if (session == null || !StringUtils.hasText(session.getDatasourceId())) {
                throw new IllegalArgumentException("会话不存在");
            }
            MessageDetail message = chatSessionService.getMessage(sessionId, messageId);
            if (message == null) {
                throw new IllegalArgumentException("消息不存在");
            }
            if (StringUtils.hasText(message.getUserFeedback())) {
                throw new IllegalStateException("该结果已反馈过");
            }
            String sqlText = StringUtils.hasText(sql) ? sql : message.getSqlText();
            if (!StringUtils.hasText(sqlText)) {
                throw new IllegalArgumentException("没有可反馈的 SQL");
            }
            int touched = relationUsageService.recordFeedback(
                    session.getDatasourceId(), sessionId, messageId, sqlText, "USEFUL".equals(feedback));
            chatSessionService.setMessageFeedback(sessionId, messageId, feedback);
            Map<String, Object> out = ok();
            out.put("touched", touched);
            out.put("feedback", feedback);
            return out;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("提交反馈失败: " + ex.getMessage(), ex);
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    private Map<String, Object> toItem(RelationUsage row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("sourceTable", row.getSourceTable());
        m.put("sourceColumn", row.getSourceColumn());
        m.put("targetTable", row.getTargetTable());
        m.put("targetColumn", row.getTargetColumn());
        m.put("hitCount", row.getHitCount());
        m.put("confidence", row.getConfidence());
        m.put("status", row.getStatus());
        m.put("displayKind", RelationUsageService.displayKind(row));
        m.put("sources", row.getSources());
        m.put("sourceDetails", relationUsageService.sourceScoreDetails(row.getSources()));
        m.put("strongSignal", RelationUsageService.hasStrongSignal(row.getSources()));
        m.put("lastUsedAt", row.getLastUsedAt());
        return m;
    }
}
