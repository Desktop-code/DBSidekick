package com.dbsidekick.controller;

import com.dbsidekick.ai.llm.OpenAiCompatibleClient;
import com.dbsidekick.text2sql.QueryRewriteService;
import com.dbsidekick.text2sql.SqlOptimizeService;
import com.dbsidekick.text2sql.Text2SqlResult;
import com.dbsidekick.text2sql.Text2SqlService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/text2sql")
public class Text2SqlController {

    private final Text2SqlService text2SqlService;
    private final OpenAiCompatibleClient deepSeekClient;
    private final SqlOptimizeService sqlOptimizeService;
    private final QueryRewriteService queryRewriteService;

    public Text2SqlController(Text2SqlService text2SqlService,
                               OpenAiCompatibleClient deepSeekClient,
                               SqlOptimizeService sqlOptimizeService,
                               QueryRewriteService queryRewriteService) {
        this.text2SqlService = text2SqlService;
        this.deepSeekClient = deepSeekClient;
        this.sqlOptimizeService = sqlOptimizeService;
        this.queryRewriteService = queryRewriteService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("llmConfigured", deepSeekClient.isConfigured());
        body.put("model", deepSeekClient.model());
        return body;
    }

    @PostMapping("/query")
    public Text2SqlResult query(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String datasourceId = stringVal(body.get("datasourceId"));
        String question = stringVal(body.get("question"));
        int topK = 5;
        if (body.get("topK") instanceof Number n) {
            topK = n.intValue();
        }

        if (!deepSeekClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先配置 DEEPSEEK_API_KEY");
        }
        if (!StringUtils.hasText(datasourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasourceId 不能为空");
        }
        if (!StringUtils.hasText(question)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }

        try {
            return text2SqlService.query(datasourceId, question, topK);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/optimize")
    public Map<String, Object> optimize(@RequestBody Map<String, Object> body) {
        String datasourceId = body == null ? null : stringVal(body.get("datasourceId"));
        String sql = body == null ? null : stringVal(body.get("sql"));
        return sqlOptimizeService.optimize(datasourceId, sql);
    }

    @PostMapping("/explain")
    public Map<String, Object> explain(@RequestBody Map<String, Object> body) {
        String datasourceId = body == null ? null : stringVal(body.get("datasourceId"));
        String sql = body == null ? null : stringVal(body.get("sql"));
        return sqlOptimizeService.explain(datasourceId, sql);
    }

    @PostMapping("/debug-rewrite")
    public Map<String, Object> debugRewrite(@RequestBody Map<String, Object> body) {
        String datasourceId = body == null ? null : stringVal(body.get("datasourceId"));
        String question = body == null ? null : stringVal(body.get("question"));
        if (!StringUtils.hasText(datasourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasourceId 不能为空");
        }
        if (!StringUtils.hasText(question)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        return queryRewriteService.rewrite(datasourceId, question).toMap();
    }

    /**
     * 诊断 Prompt 组装：历史 / 改写 / Schema / Few-shot / 最终 system+user prompt。
     * 不生成 SQL、不执行查询。
     */
    @PostMapping("/debug-prompt")
    public Map<String, Object> debugPrompt(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String datasourceId = stringVal(body.get("datasourceId"));
        String question = stringVal(body.get("question"));
        String sessionId = stringVal(body.get("sessionId"));
        int topK = 5;
        if (body.get("topK") instanceof Number n) {
            topK = n.intValue();
        }
        if (!StringUtils.hasText(datasourceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "datasourceId 不能为空");
        }
        if (!StringUtils.hasText(question)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question 不能为空");
        }
        try {
            return text2SqlService.debugPrompt(datasourceId, question, sessionId, topK);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    private static String stringVal(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return StringUtils.hasText(s) && !"null".equalsIgnoreCase(s) ? s : null;
    }
}
