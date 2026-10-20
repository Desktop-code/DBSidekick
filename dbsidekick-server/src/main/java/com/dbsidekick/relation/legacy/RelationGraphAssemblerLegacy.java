package com.dbsidekick.relation.legacy;

/**
 * 归档：RelationUsageService 中已迁移到 RelationGraphAssembler 的原实现。
 * 本类不是 Spring Bean，不参与容器扫描后的业务调用。
 * 原文含 Javadoc 结束符，因此用逐行 // 注释完整保留，避免块注释被提前截断。
 * 确认对照完成后，由人工删除本文件。
 */
public final class RelationGraphAssemblerLegacy {

    private RelationGraphAssemblerLegacy() {
    }

    // TODO: 待清理 - 已迁移至 RelationGraphAssembler，保留供对照，确认后由人工删除
    // 原方法：graph（RelationUsageService.java 第 298-417 行）
    //     /**
    //      * ER 图数据：按库过滤关系（源表与目标表都必须属于该库）；节点超过 50 时只保留关联度 Top50。
    //      * <p>SQL 过滤后再用 schema_table 表白名单做返回前二次过滤，避免漏网。</p>
    //      */
    //     public Map<String, Object> graph(String datasourceId, String database, String status) {
    //         if (!StringUtils.hasText(datasourceId)) {
    //             throw new IllegalArgumentException("datasourceId 不能为空");
    //         }
    //         String ds = datasourceId.trim();
    //         String db = StringUtils.hasText(database) ? database.trim() : null;
    //         String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
    //
    //         List<RelationUsage> raw = listAllForGraph(ds, null, st);
    //         int edgesBefore = raw.size();
    //         Set<String> tablesBefore = new LinkedHashSet<>();
    //         for (RelationUsage e : raw) {
    //             if (StringUtils.hasText(e.getSourceTable())) {
    //                 tablesBefore.add(e.getSourceTable().trim());
    //             }
    //             if (StringUtils.hasText(e.getTargetTable())) {
    //                 tablesBefore.add(e.getTargetTable().trim());
    //             }
    //         }
    //         int nodesBefore = tablesBefore.size();
    //
    //         Set<String> allowedTables = null;
    //         if (db != null) {
    //             allowedTables = loadTableNamesInDatabase(ds, db);
    //             log.info("[Sidekick][relation][graph] ds={} db={} schema_table count={}", ds, db, allowedTables.size());
    //         }
    //
    //         List<RelationUsage> filtered = new ArrayList<>();
    //         for (RelationUsage e : raw) {
    //             String src = e.getSourceTable() == null ? "" : e.getSourceTable().trim();
    //             String tgt = e.getTargetTable() == null ? "" : e.getTargetTable().trim();
    //             if (!StringUtils.hasText(src) || !StringUtils.hasText(tgt)) {
    //                 continue;
    //             }
    //             if (allowedTables != null && (!allowedTables.contains(src) || !allowedTables.contains(tgt))) {
    //                 continue;
    //             }
    //             filtered.add(e);
    //         }
    //         int edgesAfter = filtered.size();
    //
    //         Map<String, Integer> relationCount = new LinkedHashMap<>();
    //         for (RelationUsage e : filtered) {
    //             bump(relationCount, e.getSourceTable());
    //             bump(relationCount, e.getTargetTable());
    //         }
    //
    //         List<Map.Entry<String, Integer>> ranked = new ArrayList<>(relationCount.entrySet());
    //         ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    //         int totalNodes = ranked.size();
    //         boolean truncated = totalNodes > 50;
    //         Set<String> keepTables = new LinkedHashSet<>();
    //         int limit = truncated ? 50 : totalNodes;
    //         for (int i = 0; i < limit; i++) {
    //             keepTables.add(ranked.get(i).getKey());
    //         }
    //
    //         List<RelationUsage> edges = new ArrayList<>();
    //         for (RelationUsage e : filtered) {
    //             if (keepTables.contains(e.getSourceTable()) && keepTables.contains(e.getTargetTable())) {
    //                 edges.add(e);
    //             }
    //         }
    //
    //         Map<String, TableMeta> meta = loadTableMeta(ds, db, keepTables);
    //
    //         List<Map<String, Object>> nodes = new ArrayList<>();
    //         for (String table : keepTables) {
    //             // 二次兜底：有库名时绝不返回白名单外的节点
    //             if (allowedTables != null && !allowedTables.contains(table)) {
    //                 continue;
    //             }
    //             TableMeta tm = meta.getOrDefault(table, TableMeta.empty(table));
    //             Map<String, Object> node = new LinkedHashMap<>();
    //             node.put("id", table);
    //             node.put("name", table);
    //             node.put("comment", tm.comment());
    //             node.put("domain", domainOf(table));
    //             node.put("dbName", db);
    //             node.put("relationCount", relationCount.getOrDefault(table, 0));
    //             node.put("columnCount", tm.columnCount());
    //             nodes.add(node);
    //         }
    //
    //         List<Map<String, Object>> edgeItems = new ArrayList<>();
    //         for (RelationUsage e : edges) {
    //             if (allowedTables != null
    //                     && (!allowedTables.contains(e.getSourceTable()) || !allowedTables.contains(e.getTargetTable()))) {
    //                 continue;
    //             }
    //             Map<String, Object> m = new LinkedHashMap<>();
    //             m.put("id", e.getId());
    //             m.put("source", e.getSourceTable());
    //             m.put("target", e.getTargetTable());
    //             m.put("sourceColumn", e.getSourceColumn());
    //             m.put("targetColumn", e.getTargetColumn());
    //             m.put("status", e.getStatus());
    //             m.put("confidence", e.getConfidence());
    //             m.put("sources", e.getSources());
    //             edgeItems.add(m);
    //         }
    //
    //         log.info("[Sidekick][relation][graph] ds={} db={} status={} nodesBefore={} nodesAfter={} edgesBefore={} edgesAfter={} returnedNodes={} returnedEdges={}",
    //                 ds, db == null ? "(all)" : db, st == null ? "(all)" : st,
    //                 nodesBefore, totalNodes, edgesBefore, edgesAfter, nodes.size(), edgeItems.size());
    //
    //         Map<String, Object> body = new LinkedHashMap<>();
    //         body.put("database", db);
    //         body.put("nodes", nodes);
    //         body.put("edges", edgeItems);
    //         body.put("totalEdges", edgesAfter);
    //         body.put("totalNodes", totalNodes);
    //         body.put("truncated", truncated);
    //         body.put("hiddenCount", truncated ? Math.max(0, totalNodes - keepTables.size()) : 0);
    //         return body;
    //     }

    // TODO: 待清理 - 已迁移至 RelationGraphAssembler，保留供对照，确认后由人工删除
    // 原方法：bump / domainOf（RelationUsageService.java 第 517-534 行）
    //     private static void bump(Map<String, Integer> map, String table) {
    //         if (!StringUtils.hasText(table)) {
    //             return;
    //         }
    //         String key = table.trim();
    //         map.put(key, map.getOrDefault(key, 0) + 1);
    //     }
    //
    //     private static String domainOf(String table) {
    //         if (!StringUtils.hasText(table)) {
    //             return "other";
    //         }
    //         int idx = table.indexOf('_');
    //         if (idx <= 0) {
    //             return table.toLowerCase(Locale.ROOT);
    //         }
    //         return table.substring(0, idx).toLowerCase(Locale.ROOT);
    //     }

}
