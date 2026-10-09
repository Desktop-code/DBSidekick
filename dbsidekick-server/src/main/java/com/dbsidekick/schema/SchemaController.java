package com.dbsidekick.schema;

import com.dbsidekick.ai.hybrid.HybridRetrievalService;
import com.dbsidekick.ai.milvus.SchemaHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/schema")
public class SchemaController {

    private final SchemaSyncService schemaSyncService;
    private final SchemaVectorService schemaVectorService;
    private final TableAliasGenerator tableAliasGenerator;
    private final RelationInferService relationInferService;
    private final HybridRetrievalService hybridRetrievalService;

    public SchemaController(SchemaSyncService schemaSyncService,
                            SchemaVectorService schemaVectorService,
                            TableAliasGenerator tableAliasGenerator,
                            RelationInferService relationInferService,
                            HybridRetrievalService hybridRetrievalService) {
        this.schemaSyncService = schemaSyncService;
        this.schemaVectorService = schemaVectorService;
        this.tableAliasGenerator = tableAliasGenerator;
        this.relationInferService = relationInferService;
        this.hybridRetrievalService = hybridRetrievalService;
    }

    @GetMapping("/databases/{datasourceId}")
    public List<String> databases(@PathVariable("datasourceId") String datasourceId) {
        try {
            return schemaSyncService.listDatabases(datasourceId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/sync/{datasourceId}")
    public Map<String, Object> sync(@PathVariable("datasourceId") String datasourceId,
                                    @RequestParam(value = "database", required = false) String database) {
        try {
            return schemaSyncService.sync(datasourceId, database).toMap();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @GetMapping("/tables/{datasourceId}")
    public Object listTables(@PathVariable("datasourceId") String datasourceId,
                             @RequestParam(value = "database", required = false) String database) {
        try {
            return schemaSyncService.listTables(datasourceId, database);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @GetMapping("/table/{tableId}")
    public Object tableDetail(@PathVariable("tableId") String tableId) {
        try {
            return schemaSyncService.getTableDetail(tableId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/index/{datasourceId}")
    public Map<String, Object> index(@PathVariable("datasourceId") String datasourceId,
                                     @RequestParam(value = "database", required = false) String database) {
        try {
            return schemaVectorService.indexDatasource(datasourceId, database).toMap();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        long start = System.currentTimeMillis();
        try {
            if (body == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            String datasourceId = stringVal(body.get("datasourceId"));
            String query = stringVal(body.get("query"));
            int topK = 5;
            if (body.get("topK") instanceof Number n) {
                topK = n.intValue();
            }
            List<SchemaHit> hits = hybridRetrievalService.search(datasourceId, query, topK);
            List<Map<String, Object>> hitMaps = new ArrayList<>();
            for (SchemaHit hit : hits) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tableName", hit.getTableName());
                m.put("dbName", hit.getDbName());
                m.put("score", hit.getScore());
                m.put("contentPreview", preview(hit.getContent(), 200));
                hitMaps.add(m);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("hits", hitMaps);
            resp.put("elapsedMs", System.currentTimeMillis() - start);
            return resp;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /** 批量生成表别名（写 SQLite，不改 Milvus）。 */
    @PostMapping("/aliases/generate/{datasourceId}")
    public Map<String, Object> generateAliases(@PathVariable("datasourceId") String datasourceId,
                                               @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) {
        try {
            return tableAliasGenerator.generateAll(datasourceId, overwrite).toMap();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /** 规则推断外键关系。 */
    @PostMapping("/infer-relations/{datasourceId}")
    public Map<String, Object> inferRelations(@PathVariable("datasourceId") String datasourceId,
                                              @RequestParam(value = "database", required = false) String database) {
        try {
            return relationInferService.infer(datasourceId, database).toMap();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /** 调试单字段外键推断过程。 */
    @GetMapping("/infer-debug/{datasourceId}")
    public Map<String, Object> inferDebug(@PathVariable("datasourceId") String datasourceId,
                                          @RequestParam("columnName") String columnName) {
        try {
            return relationInferService.debugColumn(datasourceId, columnName);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /** 诊断：暴露真实召回排序（不改检索逻辑）。 */
    @PostMapping("/debug-search")
    public Map<String, Object> debugSearch(@RequestBody Map<String, Object> body) {
        try {
            if (body == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            String datasourceId = stringVal(body.get("datasourceId"));
            String query = stringVal(body.get("query"));
            int topK = 10;
            if (body.get("topK") instanceof Number n) {
                topK = n.intValue();
            }
            return schemaVectorService.debugSearch(datasourceId, query, topK);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /** 诊断：候选 vs Rerank 精排结果。 */
    @PostMapping("/debug-rerank")
    public Map<String, Object> debugRerank(@RequestBody Map<String, Object> body) {
        long start = System.currentTimeMillis();
        try {
            if (body == null) {
                throw new IllegalArgumentException("请求体不能为空");
            }
            String datasourceId = stringVal(body.get("datasourceId"));
            String query = stringVal(body.get("query"));
            int candidateTopK = 10;
            if (body.get("candidateTopK") instanceof Number n) {
                candidateTopK = n.intValue();
            }
            int finalTopK = 5;
            if (body.get("finalTopK") instanceof Number n) {
                finalTopK = n.intValue();
            }
            hybridRetrievalService.clearCache();
            HybridRetrievalService.SearchBundle bundle =
                    hybridRetrievalService.searchDetailed(datasourceId, query, candidateTopK, finalTopK);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("query", query);
            resp.put("candidateTopK", candidateTopK);
            resp.put("finalTopK", finalTopK);
            resp.put("denseHits", toHitMaps(bundle.dense()));
            resp.put("rrfHits", toHitMaps(bundle.candidates()));
            resp.put("candidates", toHitMaps(bundle.candidates()));
            resp.put("reranked", toHitMaps(bundle.reranked()));
            resp.put("rerankedHits", toHitMaps(bundle.reranked()));
            resp.put("fromCache", bundle.fromCache());
            resp.put("elapsedMs", System.currentTimeMillis() - start);
            return resp;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    /** 诊断：返回 Milvus 中该表完整 content 卡片。 */
    @GetMapping("/table-card/{datasourceId}/{tableName}")
    public Map<String, Object> tableCard(@PathVariable("datasourceId") String datasourceId,
                                         @PathVariable("tableName") String tableName) {
        try {
            return schemaVectorService.getTableCard(datasourceId, tableName);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    private static List<Map<String, Object>> toHitMaps(List<SchemaHit> hits) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (hits == null) {
            return list;
        }
        for (SchemaHit hit : hits) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tableName", hit.getTableName());
            m.put("dbName", hit.getDbName());
            m.put("score", hit.getScore());
            m.put("contentPreview", preview(hit.getContent(), 200));
            list.add(m);
        }
        return list;
    }

    private static String stringVal(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return StringUtils.hasText(s) && !"null".equalsIgnoreCase(s) ? s : null;
    }

    private static String preview(String content, int max) {
        if (content == null) {
            return "";
        }
        String t = content.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
