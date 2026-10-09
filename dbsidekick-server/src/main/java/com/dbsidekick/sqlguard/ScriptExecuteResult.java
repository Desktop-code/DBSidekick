package com.dbsidekick.sqlguard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多语句脚本执行结果。
 */
public class ScriptExecuteResult {

    private boolean success;
    private String script;
    private List<ScriptStepResult> steps = new ArrayList<>();
    private ScriptStepResult finalResult;
    private long totalElapsedMs;
    private String error;

    public static ScriptExecuteResult fail(String script, String error, long elapsedMs) {
        ScriptExecuteResult r = new ScriptExecuteResult();
        r.success = false;
        r.script = script;
        r.error = error;
        r.totalElapsedMs = elapsedMs;
        return r;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("script", script);
        map.put("steps", steps.stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
        map.put("totalElapsedMs", totalElapsedMs);
        map.put("error", error);
        if (finalResult != null) {
            map.put("finalResult", finalResult.toMap());
        }
        return map;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }

    public List<ScriptStepResult> getSteps() {
        return steps;
    }

    public void setSteps(List<ScriptStepResult> steps) {
        this.steps = steps == null ? new ArrayList<>() : steps;
    }

    public ScriptStepResult getFinalResult() {
        return finalResult;
    }

    public void setFinalResult(ScriptStepResult finalResult) {
        this.finalResult = finalResult;
    }

    public long getTotalElapsedMs() {
        return totalElapsedMs;
    }

    public void setTotalElapsedMs(long totalElapsedMs) {
        this.totalElapsedMs = totalElapsedMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
