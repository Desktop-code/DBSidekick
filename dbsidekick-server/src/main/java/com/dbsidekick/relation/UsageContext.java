package com.dbsidekick.relation;

/**
 * 隐式反馈上下文。得分由 {@link RelationUsageService} 按 relation_config 计算。
 *
 * @param rowCount 结果行数；小于等于 0（含 -1）不计「有行」分
 */
public record UsageContext(
        boolean sqlSuccess,
        int rowCount,
        boolean savedToScript,
        boolean userConfirmed
) {
    /** USED / SAVED / MANUAL，写入 relation_usage.sources 与 usage_log。 */
    public String sourceTag() {
        if (savedToScript) {
            return "SAVED";
        }
        if (userConfirmed) {
            return "MANUAL";
        }
        return "USED";
    }
}
