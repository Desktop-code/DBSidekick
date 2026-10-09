package com.dbsidekick.datasource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 目标库版本与能力信息。
 */
public class DbVersionInfo {

    private static final Pattern VER = Pattern.compile("(\\d+)(?:\\.(\\d+))?");

    private String product;
    private String version;
    private int major;
    private int minor;
    private boolean cteSupported;
    private boolean windowFunctionSupported;
    private boolean temporaryTableSupported = true;

    public static DbVersionInfo of(String product, String version) {
        DbVersionInfo info = new DbVersionInfo();
        info.product = product == null ? "" : product.trim();
        info.version = version == null ? "" : version.trim();
        int[] parts = parseVersion(info.version);
        info.major = parts[0];
        info.minor = parts[1];
        info.applyCapabilities();
        return info;
    }

    public static DbVersionInfo unknown(DbType type) {
        if (type == DbType.POSTGRESQL) {
            return of("PostgreSQL", "0.0");
        }
        return of("MySQL", "0.0");
    }

    private void applyCapabilities() {
        String p = product.toLowerCase(Locale.ROOT);
        if (p.contains("postgres")) {
            cteSupported = major > 8 || (major == 8 && minor >= 4) || major == 0;
            windowFunctionSupported = major >= 11 || major == 0;
            temporaryTableSupported = true;
            return;
        }
        // MySQL / MariaDB / 默认
        if (major >= 8) {
            cteSupported = true;
            windowFunctionSupported = true;
        } else if (major >= 5 && minor >= 7) {
            cteSupported = false;
            windowFunctionSupported = false;
        } else if (major == 0) {
            cteSupported = true;
            windowFunctionSupported = true;
        } else {
            cteSupported = false;
            windowFunctionSupported = false;
        }
        temporaryTableSupported = true;
    }

    private static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return new int[]{0, 0};
        }
        Matcher m = VER.matcher(version);
        if (m.find()) {
            int major = Integer.parseInt(m.group(1));
            int minor = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            return new int[]{major, minor};
        }
        return new int[]{0, 0};
    }

    public String capabilitySummary() {
        String label = StringUtilsSafe(product) + (StringUtilsSafe(version).isEmpty() ? "" : " " + version);
        return """
                当前数据库信息：
                - 数据库类型：%s
                - CTE（WITH 语句）：%s
                - 临时表：%s
                - 窗口函数：%s
                """.formatted(
                label.isBlank() ? "未知" : label,
                cteSupported ? "支持" : "不支持，请使用子查询或临时表",
                temporaryTableSupported ? "支持 CREATE TEMPORARY TABLE" : "不支持",
                windowFunctionSupported ? "支持" : "不支持"
        ).trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("product", product);
        map.put("version", version);
        map.put("major", major);
        map.put("minor", minor);
        map.put("cteSupported", cteSupported);
        map.put("windowFunctionSupported", windowFunctionSupported);
        map.put("temporaryTableSupported", temporaryTableSupported);
        return map;
    }

    private static String StringUtilsSafe(String s) {
        return s == null ? "" : s.trim();
    }

    public String getProduct() {
        return product;
    }

    public String getVersion() {
        return version;
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public boolean isCteSupported() {
        return cteSupported;
    }

    public boolean isWindowFunctionSupported() {
        return windowFunctionSupported;
    }

    public boolean isTemporaryTableSupported() {
        return temporaryTableSupported;
    }
}
