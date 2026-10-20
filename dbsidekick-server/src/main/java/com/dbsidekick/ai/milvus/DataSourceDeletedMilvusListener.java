package com.dbsidekick.ai.milvus;

import com.dbsidekick.datasource.event.DataSourceDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 数据源删除后清理该数据源的全部 Milvus 向量。异常留在本监听器内。
 */
@Component
public class DataSourceDeletedMilvusListener {

    private static final Logger log = LoggerFactory.getLogger(DataSourceDeletedMilvusListener.class);

    private final MilvusSchemaStore milvusSchemaStore;

    public DataSourceDeletedMilvusListener(MilvusSchemaStore milvusSchemaStore) {
        this.milvusSchemaStore = milvusSchemaStore;
    }

    @EventListener
    public void onDataSourceDeleted(DataSourceDeletedEvent event) {
        try {
            long deleted = milvusSchemaStore.deleteByDatasource(event.getDatasourceId());
            log.info("[Sidekick] deleted milvus vectors for datasource={} count={}",
                    event.getDatasourceId(), deleted);
        } catch (Exception ex) {
            log.warn("[Sidekick] milvus cleanup on delete failed: {}", ex.getMessage());
        }
    }
}
