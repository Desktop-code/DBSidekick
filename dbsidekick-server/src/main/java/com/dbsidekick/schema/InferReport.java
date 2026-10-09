package com.dbsidekick.schema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外键推断结果报告（写入 relation_usage）。
 */
public record InferReport(int inferredCount, int existingCount, long elapsedMs) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inferredCount", inferredCount);
        map.put("existingCount", existingCount);
        map.put("elapsedMs", elapsedMs);
        // 兼容旧前端字段名
        map.put("inferredRelations", inferredCount);
        return map;
    }
}
