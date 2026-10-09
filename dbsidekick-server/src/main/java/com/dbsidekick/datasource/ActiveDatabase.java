package com.dbsidekick.datasource;

/**
 * 当前线程正在查询的库。实例连接上执行 SQL、检索时用来限定范围。
 */
public final class ActiveDatabase {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ActiveDatabase() {
    }

    public static void set(String database) {
        if (database == null || database.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(database.trim());
        }
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
