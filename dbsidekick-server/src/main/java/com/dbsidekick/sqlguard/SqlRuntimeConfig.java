package com.dbsidekick.sqlguard;

import com.dbsidekick.config.SettingsService;
import org.springframework.stereotype.Component;

/**
 * SQL 门禁 / 执行运行时参数（来自设置页，落库 app_settings）。
 */
@Component
public class SqlRuntimeConfig {

    public static final String KEY_LIMIT_ENABLED = "sql.limitEnabled";
    public static final String KEY_DEFAULT_LIMIT = "sql.defaultLimit";
    public static final String KEY_MAX_STATEMENTS = "sql.maxStatements";
    public static final String KEY_QUERY_TIMEOUT = "sql.queryTimeoutSeconds";

    public static final boolean DEF_LIMIT_ENABLED = true;
    public static final int DEF_DEFAULT_LIMIT = 100;
    public static final int DEF_MAX_STATEMENTS = 20;
    public static final int DEF_QUERY_TIMEOUT = 30;
    /** 脚本条数硬顶，保存时钳制并提示 */
    public static final int ABS_MAX_STATEMENTS = 200;

    private final SettingsService settingsService;

    public SqlRuntimeConfig(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public boolean limitEnabled() {
        String raw = settingsService.get(KEY_LIMIT_ENABLED, String.valueOf(DEF_LIMIT_ENABLED));
        return !"false".equalsIgnoreCase(raw == null ? "" : raw.trim())
                && !"0".equals(raw == null ? "" : raw.trim());
    }

    /** 强制 LIMIT 默认值，同时作为结果集拉取上限。 */
    public int defaultLimit() {
        return clampInt(settingsService.get(KEY_DEFAULT_LIMIT, String.valueOf(DEF_DEFAULT_LIMIT)),
                1, 10_000, DEF_DEFAULT_LIMIT);
    }

    public int maxStatements() {
        return clampInt(settingsService.get(KEY_MAX_STATEMENTS, String.valueOf(DEF_MAX_STATEMENTS)),
                1, ABS_MAX_STATEMENTS, DEF_MAX_STATEMENTS);
    }

    public int queryTimeoutSeconds() {
        return clampInt(settingsService.get(KEY_QUERY_TIMEOUT, String.valueOf(DEF_QUERY_TIMEOUT)),
                1, 600, DEF_QUERY_TIMEOUT);
    }

    /** 用户 SQL 自带 LIMIT 时的上限（不低于 defaultLimit）。 */
    public int maxLimitCap() {
        return Math.max(defaultLimit(), 1000);
    }

    public static int clampStatementsForSave(int n) {
        if (n < 1) {
            return 1;
        }
        return Math.min(n, ABS_MAX_STATEMENTS);
    }

    private static int clampInt(String raw, int min, int max, int def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min) {
                return min;
            }
            if (v > max) {
                return max;
            }
            return v;
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
