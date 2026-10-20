package com.dbsidekick.relation;

import com.dbsidekick.datasource.event.DataSourceDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 数据源删除后清理 relation_usage。异常留在本监听器内，不打断删除主流程。
 */
@Component
public class DataSourceDeletedRelationListener {

    private static final Logger log = LoggerFactory.getLogger(DataSourceDeletedRelationListener.class);

    private final RelationUsageService relationUsageService;

    public DataSourceDeletedRelationListener(RelationUsageService relationUsageService) {
        this.relationUsageService = relationUsageService;
    }

    @EventListener
    public void onDataSourceDeleted(DataSourceDeletedEvent event) {
        try {
            relationUsageService.deleteByDatasource(event.getDatasourceId());
        } catch (Exception ex) {
            log.warn("[Sidekick] relation_usage cleanup on delete failed: {}", ex.getMessage());
        }
    }
}
