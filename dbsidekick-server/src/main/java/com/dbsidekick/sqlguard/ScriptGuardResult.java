package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.List;

/**
 * 脚本模式门禁结果。
 */
public class ScriptGuardResult {

    private final boolean allowed;
    private final String reason;
    private final List<String> statements;
    private final String normalizedScript;

    private ScriptGuardResult(boolean allowed, String reason, List<String> statements, String normalizedScript) {
        this.allowed = allowed;
        this.reason = reason;
        this.statements = statements == null ? List.of() : List.copyOf(statements);
        this.normalizedScript = normalizedScript;
    }

    public static ScriptGuardResult ok(List<String> statements) {
        List<String> list = statements == null ? List.of() : new ArrayList<>(statements);
        // 段与段之间空一行，注释更清晰
        return new ScriptGuardResult(true, null, list, String.join(";\n\n", list));
    }

    public static ScriptGuardResult reject(String reason) {
        return new ScriptGuardResult(false, reason, List.of(), null);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public String getReason() {
        return reason;
    }

    public List<String> getStatements() {
        return statements;
    }

    public String getNormalizedScript() {
        return normalizedScript;
    }
}
