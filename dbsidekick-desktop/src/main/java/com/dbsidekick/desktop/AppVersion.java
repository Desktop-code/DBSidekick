package com.dbsidekick.desktop;

/**
 * 应用展示版本：优先读 jar MANIFEST 的 Implementation-Version（来自 pom.xml），
 * 开发态未打进清单时回退到与 pom 一致的默认值。
 */
final class AppVersion {

    /** 与根 pom.xml &lt;version&gt; 保持一致 */
    static final String FALLBACK = "0.1.0";

    private AppVersion() {
    }

    static String current() {
        String fromManifest = AppVersion.class.getPackage() == null
                ? null
                : AppVersion.class.getPackage().getImplementationVersion();
        if (fromManifest == null || fromManifest.isBlank()) {
            return FALLBACK;
        }
        String v = fromManifest.trim();
        // 展示发布号时去掉 -SNAPSHOT 后缀
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    static String displayLabel() {
        return "v" + current();
    }
}
