package com.dbsidekick.schema;

import com.dbsidekick.datasource.DbConfig;
import java.util.List;

/**
 * Schema 抽取器。
 */
public interface SchemaExtractor {

    List<SchemaTable> extract(DbConfig config) throws Exception;

    /**
     * 抽取指定库。database 为空时与 {@link #extract(DbConfig)} 相同。
     */
    default List<SchemaTable> extract(DbConfig config, String database) throws Exception {
        return extract(config);
    }
}
