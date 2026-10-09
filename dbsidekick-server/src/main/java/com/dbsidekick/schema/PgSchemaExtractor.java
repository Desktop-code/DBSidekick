package com.dbsidekick.schema;

import com.dbsidekick.datasource.DbConfig;
import com.dbsidekick.datasource.DynamicDataSourceManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * PostgreSQL information_schema / pg_catalog 抽取。
 */
@Component
public class PgSchemaExtractor implements SchemaExtractor {

    private final DynamicDataSourceManager dataSourceManager;

    public PgSchemaExtractor(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @Override
    public List<SchemaTable> extract(DbConfig config) throws Exception {
        return extract(config, null);
    }

    @Override
    public List<SchemaTable> extract(DbConfig config, String database) throws Exception {
        String targetDb = StringUtils.hasText(database) ? database.trim() : config.getDatabase();
        Map<String, SchemaTable> tables = new LinkedHashMap<>();

        try (Connection conn = dataSourceManager.openConnection(config.getId(), targetDb)) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT n.nspname AS schema_name,
                           c.relname AS table_name,
                           obj_description(c.oid) AS table_comment
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE c.relkind = 'r'
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND n.nspname NOT LIKE 'pg_toast%'
                      AND n.nspname NOT LIKE 'pg_temp%'
                    ORDER BY n.nspname, c.relname
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SchemaTable table = new SchemaTable();
                        table.setTableName(qualifiedName(rs.getString("schema_name"), rs.getString("table_name")));
                        table.setTableComment(emptyToNull(rs.getString("table_comment")));
                        tables.put(table.getTableName(), table);
                    }
                }
            }

            Map<String, String> pkCols = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT tc.table_schema, tc.table_name, kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    WHERE tc.constraint_type = 'PRIMARY KEY'
                      AND tc.table_schema NOT IN ('pg_catalog', 'information_schema')
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = qualifiedName(rs.getString("table_schema"), rs.getString("table_name"));
                        pkCols.put(tableName + "." + rs.getString("column_name"), "1");
                    }
                }
            }

            Map<String, String> fkMap = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT kcu.table_schema, kcu.table_name, kcu.column_name,
                           ccu.table_schema AS ref_schema, ccu.table_name AS ref_table, ccu.column_name AS ref_column
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    JOIN information_schema.constraint_column_usage ccu
                      ON ccu.constraint_name = tc.constraint_name
                     AND ccu.table_schema = tc.table_schema
                    WHERE tc.constraint_type = 'FOREIGN KEY'
                      AND tc.table_schema NOT IN ('pg_catalog', 'information_schema')
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = qualifiedName(rs.getString("table_schema"), rs.getString("table_name"));
                        String key = tableName + "." + rs.getString("column_name");
                        String ref = qualifiedName(rs.getString("ref_schema"), rs.getString("ref_table"))
                                + "." + rs.getString("ref_column");
                        fkMap.put(key, ref);
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT c.table_schema, c.table_name, c.column_name, c.data_type, c.is_nullable, c.ordinal_position,
                           col_description(format('%I.%I', c.table_schema, c.table_name)::regclass::oid, c.ordinal_position) AS column_comment
                    FROM information_schema.columns c
                    WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
                    ORDER BY c.table_schema, c.table_name, c.ordinal_position
                    """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = qualifiedName(rs.getString("table_schema"), rs.getString("table_name"));
                        SchemaTable table = tables.get(tableName);
                        if (table == null) {
                            continue;
                        }
                        SchemaColumn col = new SchemaColumn();
                        col.setColumnName(rs.getString("column_name"));
                        col.setDataType(rs.getString("data_type"));
                        col.setColumnComment(emptyToNull(rs.getString("column_comment")));
                        col.setNullable(!"NO".equalsIgnoreCase(rs.getString("is_nullable")));
                        col.setPrimaryKey(pkCols.containsKey(tableName + "." + col.getColumnName()));
                        col.setOrdinalPosition(rs.getInt("ordinal_position"));
                        col.setForeignKeyRef(fkMap.get(tableName + "." + col.getColumnName()));
                        table.getColumns().add(col);
                    }
                }
            }
        }
        return new ArrayList<>(tables.values());
    }

    private static String qualifiedName(String schema, String table) {
        if (!StringUtils.hasText(schema) || "public".equalsIgnoreCase(schema)) {
            return table;
        }
        return schema.trim() + "." + table;
    }

    private static String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
