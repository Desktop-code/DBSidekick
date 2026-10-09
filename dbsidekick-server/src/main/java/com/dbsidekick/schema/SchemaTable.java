package com.dbsidekick.schema;

import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Schema 表元数据。
 */
public class SchemaTable {

    private String tableName;
    private String tableComment;
    private String tableAliases;
    private String tablePurpose;
    private List<SchemaColumn> columns = new ArrayList<>();

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getTableComment() {
        return tableComment;
    }

    public void setTableComment(String tableComment) {
        this.tableComment = tableComment;
    }

    public String getTableAliases() {
        return tableAliases;
    }

    public void setTableAliases(String tableAliases) {
        this.tableAliases = tableAliases;
    }

    public String getTablePurpose() {
        return tablePurpose;
    }

    public void setTablePurpose(String tablePurpose) {
        this.tablePurpose = tablePurpose;
    }

    public List<SchemaColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<SchemaColumn> columns) {
        this.columns = columns == null ? new ArrayList<>() : columns;
    }

    /**
     * 生成结构化卡片文本，供下一轮向量化使用。
     */
    public String toCardText() {
        StringBuilder sb = new StringBuilder();
        sb.append("表名：").append(nullToEmpty(tableName));
        if (StringUtils.hasText(tableComment)) {
            sb.append("（").append(tableComment.trim()).append("）");
        }
        sb.append('\n').append("字段：\n");
        for (SchemaColumn col : columns) {
            sb.append("- ").append(nullToEmpty(col.getColumnName()));
            if (StringUtils.hasText(col.getDataType())) {
                sb.append(' ').append(col.getDataType());
            }
            if (col.isPrimaryKey()) {
                sb.append(" 主键");
            }
            if (StringUtils.hasText(col.getForeignKeyRef())) {
                sb.append(" 外键→").append(col.getForeignKeyRef());
            }
            if (StringUtils.hasText(col.getColumnComment())) {
                sb.append(' ').append(col.getColumnComment().trim());
            }
            sb.append('\n');
        }
        List<String> fks = new ArrayList<>();
        for (SchemaColumn col : columns) {
            if (StringUtils.hasText(col.getForeignKeyRef())) {
                String ref = col.getForeignKeyRef();
                int dot = ref.indexOf('.');
                if (dot > 0) {
                    fks.add(col.getColumnName() + " → " + ref.substring(0, dot) + "(" + ref.substring(dot + 1) + ")");
                } else {
                    fks.add(col.getColumnName() + " → " + ref);
                }
            }
        }
        if (!fks.isEmpty()) {
            sb.append("外键关系：\n");
            for (String fk : fks) {
                sb.append("- ").append(fk).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
