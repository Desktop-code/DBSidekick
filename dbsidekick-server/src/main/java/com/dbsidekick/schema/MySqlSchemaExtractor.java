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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * MySQL information_schema 抽取。
 */
@Component
public class MySqlSchemaExtractor implements SchemaExtractor {

    private static final Set<String> EXCLUDED = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private final DynamicDataSourceManager dataSourceManager;

    public MySqlSchemaExtractor(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    @Override
    public List<SchemaTable> extract(DbConfig config) throws Exception {
        return extract(config, null);
    }

    @Override
    public List<SchemaTable> extract(DbConfig config, String database) throws Exception {
        String db = StringUtils.hasText(database) ? database.trim() : config.getDatabase();
        if (!StringUtils.hasText(db) || EXCLUDED.contains(db.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("非法的 database: " + db);
        }

        Map<String, SchemaTable> tables = new LinkedHashMap<>();
        try (Connection conn = dataSourceManager.getDataSource(config.getId()).getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT TABLE_NAME, TABLE_COMMENT
                    FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
                    ORDER BY TABLE_NAME
                    """)) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SchemaTable table = new SchemaTable();
                        table.setTableName(rs.getString("TABLE_NAME"));
                        table.setTableComment(emptyToNull(rs.getString("TABLE_COMMENT")));
                        tables.put(table.getTableName(), table);
                    }
                }
            }

            Map<String, String> fkMap = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
                    FROM information_schema.KEY_COLUMN_USAGE
                    WHERE TABLE_SCHEMA = ?
                      AND REFERENCED_TABLE_NAME IS NOT NULL
                    """)) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString("TABLE_NAME") + "." + rs.getString("COLUMN_NAME");
                        fkMap.put(key, rs.getString("REFERENCED_TABLE_NAME") + "." + rs.getString("REFERENCED_COLUMN_NAME"));
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, COLUMN_COMMENT,
                           IS_NULLABLE, COLUMN_KEY, ORDINAL_POSITION
                    FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = ?
                    ORDER BY TABLE_NAME, ORDINAL_POSITION
                    """)) {
                ps.setString(1, db);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        SchemaTable table = tables.get(tableName);
                        if (table == null) {
                            continue;
                        }
                        SchemaColumn col = new SchemaColumn();
                        col.setColumnName(rs.getString("COLUMN_NAME"));
                        String columnType = rs.getString("COLUMN_TYPE");
                        col.setDataType(StringUtils.hasText(columnType) ? columnType : rs.getString("DATA_TYPE"));
                        col.setColumnComment(emptyToNull(rs.getString("COLUMN_COMMENT")));
                        col.setNullable(!"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE")));
                        col.setPrimaryKey("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")));
                        col.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                        col.setForeignKeyRef(fkMap.get(tableName + "." + col.getColumnName()));
                        table.getColumns().add(col);
                    }
                }
            }
        }
        return new ArrayList<>(tables.values());
    }

    private static String emptyToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
