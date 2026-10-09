package com.dbsidekick.controller;

import com.dbsidekick.asset.ScriptDetail;
import com.dbsidekick.asset.ScriptService;
import com.dbsidekick.asset.ScriptSummary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/script")
public class ScriptController {

    private final ScriptService scriptService;

    public ScriptController(ScriptService scriptService) {
        this.scriptService = scriptService;
    }

    @GetMapping("/list")
    public List<ScriptSummary> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "onlyFav", required = false) Boolean onlyFav) {
        try {
            return scriptService.list(keyword, onlyFav);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "查询脚本失败" : ex.getMessage(), ex);
        }
    }

    @GetMapping("/{id}")
    public ScriptDetail get(@PathVariable("id") String id) {
        try {
            return scriptService.get(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "获取脚本失败" : ex.getMessage(), ex);
        }
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody ScriptDetail detail) {
        try {
            String id = scriptService.save(detail);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "保存脚本失败" : ex.getMessage(), ex);
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id) {
        try {
            scriptService.delete(id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "删除脚本失败" : ex.getMessage(), ex);
        }
    }

    @PutMapping("/{id}/favorite")
    public Map<String, Object> favorite(@PathVariable("id") String id) {
        try {
            scriptService.toggleFavorite(id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "切换收藏失败" : ex.getMessage(), ex);
        }
    }
}
