package com.dbsidekick.ai.milvus;

/**
 * Schema 向量检索命中。
 */
public class SchemaHit {

    private String tableName;
    private String dbName;
    private String content;
    private float score;

    public SchemaHit() {
    }

    public SchemaHit(String tableName, String dbName, String content, float score) {
        this.tableName = tableName;
        this.dbName = dbName;
        this.content = content;
        this.score = score;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public float getScore() {
        return score;
    }

    public void setScore(float score) {
        this.score = score;
    }
}
