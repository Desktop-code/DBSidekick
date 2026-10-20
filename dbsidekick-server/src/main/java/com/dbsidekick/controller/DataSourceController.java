package com.dbsidekick.controller;

import com.dbsidekick.ai.milvus.MilvusSchemaStore;
import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import com.dbsidekick.schema.SchemaSyncService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DataSourceController.class);

    private final DynamicDataSourceManager dataSourceManager;
    private final SchemaSyncService schemaSyncService;
    private final MilvusSchemaStore milvusSchemaStore;

    public DataSourceController(DynamicDataSourceManager dataSourceManager,
                                SchemaSyncService schemaSyncService,
                                MilvusSchemaStore milvusSchemaStore) {
        this.dataSourceManager = dataSourceManager;
        this.schemaSyncService = schemaSyncService;
        this.milvusSchemaStore = milvusSchemaStore;
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
            Map<String, Object> body = dataSourceManager.deleteCheck(id);
            long milvusVectorCount = 0L;
            try {
                milvusVectorCount = milvusSchemaStore.countByDatasource(id);
            } catch (Exception ex) {
                log.warn("[Sidekick] delete-check milvus count failed: {}", ex.getMessage());
            }
            int schemaTableCount = intVal(body.get("schemaTableCount"));
            int sessionCount = intVal(body.get("sessionCount"));
            int scriptCount = intVal(body.get("scriptCount"));
            body.put("milvusVectorCount", milvusVectorCount);
            body.put("warning", deleteWarning(schemaTableCount, milvusVectorCount, sessionCount, scriptCount));
            return body;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "预检失败" : ex.getMessage(), ex);
        }
    }

    private static int intVal(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String deleteWarning(int schemaTableCount, long milvusVectorCount,
                                        int sessionCount, int scriptCount) {
        if (schemaTableCount == 0 && milvusVectorCount == 0 && sessionCount == 0 && scriptCount == 0) {
            return "";
        }
        return String.format(
                "将同时删除 %d 张表的结构、%d 条向量索引；%d 个会话和 %d 个脚本将失去关联数据源",
                schemaTableCount, milvusVectorCount, sessionCount, scriptCount);
    }

    /** 实例下的业务库。会话创建和切库弹窗都用这份列表。 */
    @GetMapping("/{id}/databases")
    public List<String> databases(@PathVariable("id") String id) {
        try {
            return schemaSyncService.listDatabases(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ex.getMessage() == null ? "读取数据库列表失败" : ex.getMessage(), ex);
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
