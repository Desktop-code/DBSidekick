package com.dbsidekick.datasource;

/**
 * 支持的数据库类型。
 */
public enum DbType {

    MYSQL("com.mysql.cj.jdbc.Driver", 3306) {
        @Override
        public String buildJdbcUrl(DbConfig config) {
            String db = nullToEmpty(config.getDatabase());
            String path = db.isEmpty() ? "/" : "/" + db;
            return "jdbc:mysql://" + config.getHost() + ":" + resolvePort(config)
                    + path
                    + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true";
        }
    },
    POSTGRESQL("org.postgresql.Driver", 5432) {
        @Override
        public String buildJdbcUrl(DbConfig config) {
            String db = nullToEmpty(config.getDatabase());
            if (db.isEmpty()) {
                db = "postgres";
            }
            StringBuilder url = new StringBuilder("jdbc:postgresql://")
                    .append(config.getHost()).append(":").append(resolvePort(config))
                    .append("/").append(db);
            if (config.getSchema() != null && !config.getSchema().isBlank()) {
                url.append("?currentSchema=").append(config.getSchema().trim());
            }
            return url.toString();
        }
    };

    private final String driverClassName;
    private final int defaultPort;

    DbType(String driverClassName, int defaultPort) {
        this.driverClassName = driverClassName;
        this.defaultPort = defaultPort;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public abstract String buildJdbcUrl(DbConfig config);

    protected int resolvePort(DbConfig config) {
        return config.getPort() != null && config.getPort() > 0 ? config.getPort() : defaultPort;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
