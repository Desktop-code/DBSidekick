package com.dbsidekick.controller;

import com.dbsidekick.relation.RelationConfigService;
import com.dbsidekick.relation.RelationUsage;
import com.dbsidekick.relation.RelationUsageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    public RelationController(RelationUsageService relationUsageService,
                              RelationConfigService relationConfigService) {
        this.relationUsageService = relationUsageService;
        this.relationConfigService = relationConfigService;
    }

    @GetMapping("/list")
    public Map<String, Object> list(
            @RequestParam("datasourceId") String datasourceId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String st = StringUtils.hasText(status) ? status.trim() : null;
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        List<RelationUsage> rows = relationUsageService.list(datasourceId, st, kw, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (RelationUsage row : rows) {
            items.add(toItem(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", relationUsageService.count(datasourceId, st, kw));
        body.put("items", items);
        return body;
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

    private static Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        return body;
    }

    private static Map<String, Object> toItem(RelationUsage row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("sourceTable", row.getSourceTable());
        m.put("sourceColumn", row.getSourceColumn());
        m.put("targetTable", row.getTargetTable());
        m.put("targetColumn", row.getTargetColumn());
        m.put("hitCount", row.getHitCount());
        m.put("confidence", row.getConfidence());
        m.put("status", row.getStatus());
        m.put("sources", row.getSources());
        m.put("lastUsedAt", row.getLastUsedAt());
        return m;
    }
}
