package com.dbsidekick.relation;

import com.dbsidekick.relation.RelationUsageRepository.SchemaTableMeta;
import com.dbsidekick.relation.RelationUsageRepository.TableEdge;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 把关系行组装成 ER 图：库白名单、关联度 Top50、节点与边。SQL 全部交给 {@link RelationUsageRepository}。
 */
@Component
public class RelationGraphAssembler {

    private static final Logger log = LoggerFactory.getLogger(RelationGraphAssembler.class);
    private static final int NODE_LIMIT = 50;

    private final RelationUsageRepository repository;

    public RelationGraphAssembler(RelationUsageRepository repository) {
        this.repository = repository;
    }

    /**
     * ER 图数据。指定库时源表和目标表都必须属于该库；节点超过 50 时只保留关联度 Top50。
     * 原实现见 relation/legacy/RelationGraphAssemblerLegacy。
     */
    public Map<String, Object> graph(String datasourceId, String database, String status) {
        if (!StringUtils.hasText(datasourceId)) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        String ds = datasourceId.trim();
        String db = StringUtils.hasText(database) ? database.trim() : null;
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;

        List<RelationUsage> raw = loadEdges(ds, st);
        int edgesBefore = raw.size();
        Set<String> tablesBefore = new LinkedHashSet<>();
        for (RelationUsage edge : raw) {
            if (StringUtils.hasText(edge.getSourceTable())) {
                tablesBefore.add(edge.getSourceTable().trim());
            }
            if (StringUtils.hasText(edge.getTargetTable())) {
                tablesBefore.add(edge.getTargetTable().trim());
            }
        }
        int nodesBefore = tablesBefore.size();

        Set<String> allowedTables = null;
        if (db != null) {
            allowedTables = loadAllowedTables(ds, db);
            log.info("[Sidekick][relation][graph] ds={} db={} schema_table count={}", ds, db, allowedTables.size());
        }

        List<RelationUsage> filtered = new ArrayList<>();
        for (RelationUsage edge : raw) {
            String src = edge.getSourceTable() == null ? "" : edge.getSourceTable().trim();
            String tgt = edge.getTargetTable() == null ? "" : edge.getTargetTable().trim();
            if (!StringUtils.hasText(src) || !StringUtils.hasText(tgt)) {
                continue;
            }
            if (allowedTables != null && (!allowedTables.contains(src) || !allowedTables.contains(tgt))) {
                continue;
            }
            filtered.add(edge);
        }
        int edgesAfter = filtered.size();

        Map<String, Integer> relationCount = new LinkedHashMap<>();
        for (RelationUsage edge : filtered) {
            bump(relationCount, edge.getSourceTable());
            bump(relationCount, edge.getTargetTable());
        }

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(relationCount.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int totalNodes = ranked.size();
        boolean truncated = totalNodes > NODE_LIMIT;
        Set<String> keepTables = new LinkedHashSet<>();
        int limit = truncated ? NODE_LIMIT : totalNodes;
        for (int i = 0; i < limit; i++) {
            keepTables.add(ranked.get(i).getKey());
        }

        List<RelationUsage> edges = new ArrayList<>();
        for (RelationUsage edge : filtered) {
            if (keepTables.contains(edge.getSourceTable()) && keepTables.contains(edge.getTargetTable())) {
                edges.add(edge);
            }
        }

        Map<String, SchemaTableMeta> meta = repository.loadTableMeta(ds, db, keepTables);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String table : keepTables) {
            if (allowedTables != null && !allowedTables.contains(table)) {
                continue;
            }
            SchemaTableMeta tableMeta = meta.getOrDefault(table, SchemaTableMeta.empty(table));
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", table);
            node.put("name", table);
            node.put("comment", tableMeta.comment());
            node.put("domain", domainOf(table));
            node.put("dbName", db);
            node.put("relationCount", relationCount.getOrDefault(table, 0));
            node.put("columnCount", tableMeta.columnCount());
            nodes.add(node);
        }

        List<Map<String, Object>> edgeItems = new ArrayList<>();
        for (RelationUsage edge : edges) {
            if (allowedTables != null
                    && (!allowedTables.contains(edge.getSourceTable())
                    || !allowedTables.contains(edge.getTargetTable()))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", edge.getId());
            item.put("source", edge.getSourceTable());
            item.put("target", edge.getTargetTable());
            item.put("sourceColumn", edge.getSourceColumn());
            item.put("targetColumn", edge.getTargetColumn());
            item.put("status", edge.getStatus());
            item.put("confidence", edge.getConfidence());
            item.put("sources", edge.getSources());
            edgeItems.add(item);
        }

        log.info("[Sidekick][relation][graph] ds={} db={} status={} nodesBefore={} nodesAfter={} edgesBefore={} edgesAfter={} returnedNodes={} returnedEdges={}",
                ds, db == null ? "(all)" : db, st == null ? "(all)" : st,
                nodesBefore, totalNodes, edgesBefore, edgesAfter, nodes.size(), edgeItems.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("database", db);
        body.put("nodes", nodes);
        body.put("edges", edgeItems);
        body.put("totalEdges", edgesAfter);
        body.put("totalNodes", totalNodes);
        body.put("truncated", truncated);
        body.put("hiddenCount", truncated ? Math.max(0, totalNodes - keepTables.size()) : 0);
        return body;
    }

    private List<RelationUsage> loadEdges(String datasourceId, String status) {
        try {
            return repository.listForGraph(datasourceId, status);
        } catch (Exception ex) {
            throw new IllegalStateException("查询关系图失败: " + ex.getMessage(), ex);
        }
    }

    private Set<String> loadAllowedTables(String datasourceId, String database) {
        try {
            return repository.loadTableNames(datasourceId, database);
        } catch (Exception ex) {
            throw new IllegalStateException("读取 schema_table 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 候选表经由 CONFIRMED 关系连到的邻接表，不含候选表自身。
     */
    public Set<String> confirmedNeighbors(String datasourceId, Collection<String> seedTables) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> seeds = new LinkedHashSet<>();
        if (seedTables != null) {
            for (String table : seedTables) {
                if (StringUtils.hasText(table)) {
                    seeds.add(table.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!StringUtils.hasText(datasourceId) || seeds.isEmpty()) {
            return out;
        }
        try {
            for (TableEdge edge : repository.listConfirmedEdges(datasourceId.trim())) {
                addNeighbor(out, seeds, edge.sourceTable(), edge.targetTable());
                addNeighbor(out, seeds, edge.targetTable(), edge.sourceTable());
            }
        } catch (Exception ex) {
            log.warn("[Sidekick][relation] neighbor lookup failed: {}", ex.getMessage());
        }
        return out;
    }

    private static void addNeighbor(Set<String> out, Set<String> seeds, String self, String other) {
        if (self == null || other == null) {
            return;
        }
        if (seeds.contains(self.toLowerCase(Locale.ROOT)) && !seeds.contains(other.toLowerCase(Locale.ROOT))) {
            out.add(other);
        }
    }

    private static void bump(Map<String, Integer> map, String table) {
        if (!StringUtils.hasText(table)) {
            return;
        }
        String key = table.trim();
        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    private static String domainOf(String table) {
        if (!StringUtils.hasText(table)) {
            return "other";
        }
        int idx = table.indexOf('_');
        if (idx <= 0) {
            return table.toLowerCase(Locale.ROOT);
        }
        return table.substring(0, idx).toLowerCase(Locale.ROOT);
    }
}
