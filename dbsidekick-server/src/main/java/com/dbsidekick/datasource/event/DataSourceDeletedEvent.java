package com.dbsidekick.datasource.event;

import org.springframework.context.ApplicationEvent;

/**
 * 数据源的连接池和本地配置已经删除。relation 与 ai 各自监听并清理自己的数据。
 */
public class DataSourceDeletedEvent extends ApplicationEvent {

    private final String datasourceId;

    public DataSourceDeletedEvent(Object source, String datasourceId) {
        super(source);
        this.datasourceId = datasourceId;
    }

    public String getDatasourceId() {
        return datasourceId;
    }
}
