package com.dbsidekick.datasource;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 数据源连接配置（运行时内存保存）。
 */
public class DbConfig {

    private String id;
    private String name;
    private DbType type;
    private String host;
    private Integer port;
    private String database;
    /** PostgreSQL schema；MySQL 可空 */
    private String schema;
    private String username;
    private String password;
    private boolean enabled = true;
    /** 如 MySQL / PostgreSQL */
    private String dbProduct;
    /** 如 8.0.36 */
    private String dbVersion;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public DbType getType() {
        return type;
    }

    public void setType(DbType type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDbProduct() {
        return dbProduct;
    }

    public void setDbProduct(String dbProduct) {
        this.dbProduct = dbProduct;
    }

    public String getDbVersion() {
        return dbVersion;
    }

    public void setDbVersion(String dbVersion) {
        this.dbVersion = dbVersion;
    }

    /** 列表脱敏副本（不含密码）。 */
    public DbConfig maskedCopy() {
        DbConfig copy = new DbConfig();
        copy.setId(this.id);
        copy.setName(this.name);
        copy.setType(this.type);
        copy.setHost(this.host);
        copy.setPort(this.port);
        copy.setDatabase(this.database);
        copy.setSchema(this.schema);
        copy.setUsername(this.username);
        copy.setPassword(null);
        copy.setEnabled(this.enabled);
        copy.setDbProduct(this.dbProduct);
        copy.setDbVersion(this.dbVersion);
        return copy;
    }
}
