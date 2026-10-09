package com.dbsidekick.controller;

import com.dbsidekick.datasource.ActiveDatabase;
import com.dbsidekick.sqlguard.SqlExecuteResult;
import com.dbsidekick.sqlguard.SqlExecutor;
import com.dbsidekick.sqlguard.SqlExplainService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sql")
public class SqlController {

    private final SqlExecutor sqlExecutor;
    private final SqlExplainService sqlExplainService;

    public SqlController(SqlExecutor sqlExecutor, SqlExplainService sqlExplainService) {
        this.sqlExecutor = sqlExecutor;
        this.sqlExplainService = sqlExplainService;
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody Map<String, String> body) {
        String datasourceId = body == null ? null : body.get("datasourceId");
        String sql = body == null ? null : body.get("sql");
        ActiveDatabase.set(body == null ? null : body.get("database"));
        try {
            SqlExecuteResult result = sqlExecutor.execute(datasourceId, sql);
            return result.toMap();
        } finally {
            ActiveDatabase.clear();
        }
    }

    @PostMapping("/explain-plan")
    public Map<String, Object> explainPlan(@RequestBody Map<String, String> body) {
        String datasourceId = body == null ? null : body.get("datasourceId");
        String sql = body == null ? null : body.get("sql");
        ActiveDatabase.set(body == null ? null : body.get("database"));
        try {
            return sqlExplainService.explain(datasourceId, sql);
        } finally {
            ActiveDatabase.clear();
        }
    }
}
