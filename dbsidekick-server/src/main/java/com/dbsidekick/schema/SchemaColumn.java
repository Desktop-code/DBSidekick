package com.dbsidekick.schema;

/**
 * Schema 列元数据。
 */
public class SchemaColumn {

    private String columnName;
    private String dataType;
    private String columnComment;
    private boolean primaryKey;
    private boolean nullable = true;
    private String foreignKeyRef;
    /** 1=规则推断外键，0=库内真实外键或未设置 */
    private boolean inferred;
    private int ordinalPosition;

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getColumnComment() {
        return columnComment;
    }

    public void setColumnComment(String columnComment) {
        this.columnComment = columnComment;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(boolean primaryKey) {
        this.primaryKey = primaryKey;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public String getForeignKeyRef() {
        return foreignKeyRef;
    }

    public void setForeignKeyRef(String foreignKeyRef) {
        this.foreignKeyRef = foreignKeyRef;
    }

    public boolean isInferred() {
        return inferred;
    }

    public void setInferred(boolean inferred) {
        this.inferred = inferred;
    }

    public int getOrdinalPosition() {
        return ordinalPosition;
    }

    public void setOrdinalPosition(int ordinalPosition) {
        this.ordinalPosition = ordinalPosition;
    }
}
