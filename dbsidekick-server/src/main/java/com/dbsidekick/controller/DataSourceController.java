package com.dbsidekick.controller;

import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/datasource")
public class DataSourceController {

    private final DynamicDataSourceManager dataSourceManager;

    public DataSourceController(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @PostMapping("/add")
    public Map<String, Object> add(@RequestBody DbConfig config) {
        try {
            String id = dataSourceManager.addDataSource(config);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ex.getMessage() == null ? "添加数据源失败" : ex.getMessage());
        }
    }

    @PostMapping("/update")
    public Map<String, Object> update(@RequestBody DbConfig config) {
        try {
            dataSourceManager.updateDataSource(config);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ex.getMessage() == null ? "更新数据源失败" : ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable("id") String id) {
        try {
            dataSourceManager.removeDataSource(id);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "删除失败" : ex.getMessage(), ex);
        }
    }

    @GetMapping("/{id}/delete-check")
    public Map<String, Object> deleteCheck(@PathVariable("id") String id) {
        try {
            return dataSourceManager.deleteCheck(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "预检失败" : ex.getMessage(), ex);
        }
    }

    @GetMapping("/list")
    public Object list() {
        return dataSourceManager.getAllConfigs();
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody DbConfig config) {
        try {
            return dataSourceManager.testConnection(config);
        } catch (IllegalArgumentException ex) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("latencyMs", 0);
            body.put("message", ex.getMessage());
            return body;
        }
    }
}
