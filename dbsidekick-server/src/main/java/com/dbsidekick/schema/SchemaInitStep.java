package com.dbsidekick.schema;

/**
 * 一键初始化的四个步骤。
 */
public enum SchemaInitStep {
    SYNC,
    INFER,
    ALIAS,
    INDEX;

    public String displayName() {
        return switch (this) {
            case SYNC -> "同步表结构";
            case INFER -> "推断表关系";
            case ALIAS -> "生成表别名";
            case INDEX -> "建立向量索引";
        };
    }
}
