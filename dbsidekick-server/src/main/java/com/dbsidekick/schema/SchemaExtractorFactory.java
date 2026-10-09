package com.dbsidekick.schema;

import com.dbsidekick.datasource.DbType;
import org.springframework.stereotype.Component;

@Component
public class SchemaExtractorFactory {

    private final MySqlSchemaExtractor mySqlSchemaExtractor;
    private final PgSchemaExtractor pgSchemaExtractor;

    public SchemaExtractorFactory(MySqlSchemaExtractor mySqlSchemaExtractor, PgSchemaExtractor pgSchemaExtractor) {
        this.mySqlSchemaExtractor = mySqlSchemaExtractor;
        this.pgSchemaExtractor = pgSchemaExtractor;
    }

    public SchemaExtractor get(DbType type) {
        if (type == null) {
            throw new IllegalArgumentException("DbType 不能为空");
        }
        return switch (type) {
            case MYSQL -> mySqlSchemaExtractor;
            case POSTGRESQL -> pgSchemaExtractor;
        };
    }
}
