package com.dbsidekick.relation;

/**
 * 归一化后的表关系对（source/target 已按字典序固定方向）。
 */
public record RelationPair(
        String sourceTable,
        String sourceColumn,
        String targetTable,
        String targetColumn
) {
    public static RelationPair normalized(String tableA, String colA, String tableB, String colB) {
        String t1 = tableA == null ? "" : tableA.trim();
        String c1 = colA == null ? "" : colA.trim();
        String t2 = tableB == null ? "" : tableB.trim();
        String c2 = colB == null ? "" : colB.trim();
        String k1 = (t1 + "." + c1).toLowerCase();
        String k2 = (t2 + "." + c2).toLowerCase();
        if (k1.compareTo(k2) <= 0) {
            return new RelationPair(t1, c1, t2, c2);
        }
        return new RelationPair(t2, c2, t1, c1);
    }
}
