package com.dbsidekick.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    /** 与根 pom.xml &lt;version&gt; 一致；开发态无 MANIFEST 时回退 */
    private static final String FALLBACK_VERSION = "0.1.0";

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app", "dbsidekick");
        body.put("version", resolveVersion());
        return body;
    }

    private static String resolveVersion() {
        Package pkg = HealthController.class.getPackage();
        String fromManifest = pkg == null ? null : pkg.getImplementationVersion();
        if (fromManifest == null || fromManifest.isBlank()) {
            return FALLBACK_VERSION;
        }
        String v = fromManifest.trim();
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }
}
