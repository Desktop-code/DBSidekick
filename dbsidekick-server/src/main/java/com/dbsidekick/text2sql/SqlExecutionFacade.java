package com.dbsidekick.text2sql;

import com.dbsidekick.relation.RelationUsageService;
import com.dbsidekick.relation.UsageContext;
import com.dbsidekick.sqlguard.ScriptExecuteResult;
import com.dbsidekick.sqlguard.ScriptExecutor;
import com.dbsidekick.sqlguard.ScriptGuardResult;
import com.dbsidekick.sqlguard.ScriptStepResult;
import com.dbsidekick.sqlguard.SqlExecuteResult;
import com.dbsidekick.sqlguard.SqlExecutor;
import com.dbsidekick.sqlguard.SqlGuard;
import com.dbsidekick.sqlguard.SqlGuardResult;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 单轮 SQL 门禁、执行与成功后的示例 / 关系反馈。
 * 失败且尚未重试过时返回 {@link ExecutionPass#needsRetry()}，由流水线决定是否再生成一次。
 */
@Component
public class SqlExecutionFacade {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionFacade.class);

    private final SqlGuard sqlGuard;
    private final SqlExecutor sqlExecutor;
    private final ScriptExecutor scriptExecutor;
    private final FewShotService fewShotService;
    private final RelationUsageService relationUsageService;

    public SqlExecutionFacade(SqlGuard sqlGuard,
                              SqlExecutor sqlExecutor,
                              ScriptExecutor scriptExecutor,
                              FewShotService fewShotService,
                              RelationUsageService relationUsageService) {
        this.sqlGuard = sqlGuard;
        this.sqlExecutor = sqlExecutor;
        this.scriptExecutor = scriptExecutor;
        this.fewShotService = fewShotService;
        this.relationUsageService = relationUsageService;
    }

    /**
     * 按脚本或简单模式做一轮校验和执行。
     *
     * @param retried 为 true 时本轮失败直接返回最终结果，不再要求重试
     */
    public ExecutionPass execute(String question, String datasourceId, String sessionId, String rawSql,
                                 ReasoningTrace trace, long pipelineStart, boolean retried,
                                 boolean scriptMode, Set<String> allowedTables) {
        if (scriptMode) {
            return executeScript(question, datasourceId, sessionId, rawSql, trace, pipelineStart, retried,
                    allowedTables);
        }
        return executeSimple(question, datasourceId, sessionId, rawSql, trace, pipelineStart, retried,
                allowedTables);
    }

    private ExecutionPass executeSimple(String question, String datasourceId, String sessionId, String rawSql,
                                        ReasoningTrace trace, long pipelineStart, boolean retried,
                                        Set<String> allowedTables) {
        long t0 = System.currentTimeMillis();
        SqlGuardResult guard = sqlGuard.validateSimple(rawSql, allowedTables);
        long guardMs = System.currentTimeMillis() - t0;
        if (!guard.isAllowed()) {
            trace.emit("安全校验", "不通过: " + guard.getReason(), guardMs);
            if (!retried) {
                return ExecutionPass.retry(rawSql, guard.getReason(), false);
            }
            Text2SqlResult fail = Text2SqlResult.fail(question, guard.getReason(), trace.steps(),
                    System.currentTimeMillis() - pipelineStart);
            fail.setRawSql(rawSql);
            fail.setScriptMode(false);
            return ExecutionPass.failed(fail);
        }
        String normalizedSql = guard.getNormalizedSql();
        trace.emit("安全校验", "通过，normalizedSql 已就绪", guardMs);

        t0 = System.currentTimeMillis();
        SqlExecuteResult exec = sqlExecutor.execute(datasourceId, normalizedSql);
        long execMs = System.currentTimeMillis() - t0;

        if (exec.isSuccess()) {
            trace.emit("执行",
                    "成功，返回 " + exec.getRowCount() + " 行" + (exec.isTruncated() ? "（已截断）" : ""),
                    execMs);
            fewShotService.trySave(datasourceId, sessionId, question, normalizedSql, exec.getRowCount());
            try {
                relationUsageService.recordUsage(
                        datasourceId,
                        sessionId,
                        Text2SqlService.questionHash(question),
                        normalizedSql,
                        new UsageContext(true, exec.getRowCount(), false, false));
            } catch (Exception ex) {
                log.warn("[Sidekick][text2sql] relation usage record skipped: {}", ex.getMessage());
            }
            Text2SqlResult ok = new Text2SqlResult();
            ok.setQuestion(question);
            ok.setSql(normalizedSql);
            ok.setRawSql(rawSql);
            ok.setColumns(exec.getColumns());
            ok.setRows(exec.getRows());
            ok.setRowCount(exec.getRowCount());
            ok.setTruncated(exec.isTruncated());
            ok.setReasoningSteps(trace.steps());
            ok.setSuccess(true);
            ok.setScriptMode(false);
            ok.setElapsedMs(System.currentTimeMillis() - pipelineStart);
            return ExecutionPass.success(ok);
        }

        String err = exec.getError() == null ? "执行失败" : exec.getError();
        trace.emit("执行", "失败: " + err, execMs);
        if (!retried) {
            return ExecutionPass.retry(normalizedSql, err, false);
        }
        Text2SqlResult fail = Text2SqlResult.fail(question, err, trace.steps(),
                System.currentTimeMillis() - pipelineStart);
        fail.setSql(normalizedSql);
        fail.setRawSql(rawSql);
        fail.setScriptMode(false);
        return ExecutionPass.failed(fail);
    }

    private ExecutionPass executeScript(String question, String datasourceId, String sessionId, String rawSql,
                                        ReasoningTrace trace, long pipelineStart, boolean retried,
                                        Set<String> allowedTables) {
        long t0 = System.currentTimeMillis();
        ScriptGuardResult guard = sqlGuard.validateScript(rawSql, true, allowedTables);
        long guardMs = System.currentTimeMillis() - t0;
        if (!guard.isAllowed()) {
            String reason = guard.getReason() == null ? "安全校验未通过" : guard.getReason();
            trace.emit("安全校验", "不通过: " + reason, guardMs);
            if (!retried) {
                return ExecutionPass.retry(rawSql, reason, true);
            }
            Text2SqlResult fail = Text2SqlResult.fail(question, reason, trace.steps(),
                    System.currentTimeMillis() - pipelineStart);
            fail.setRawSql(rawSql);
            fail.setScriptMode(true);
            return ExecutionPass.failed(fail);
        }
        trace.emit("安全校验", "通过，共 " + guard.getStatements().size() + " 条语句", guardMs);
        trace.emit("脚本模式", "生成 " + guard.getStatements().size() + " 条语句", 0);

        t0 = System.currentTimeMillis();
        ScriptExecuteResult exec = scriptExecutor.executeValidated(datasourceId, guard);
        long execMs = System.currentTimeMillis() - t0;

        if (exec.isSuccess() && exec.getFinalResult() != null) {
            ScriptStepResult finalStep = exec.getFinalResult();
            trace.emit("执行",
                    "成功，返回 " + finalStep.getRowCount() + " 行"
                            + (finalStep.isTruncated() ? "（已截断）" : "")
                            + "，步骤 " + exec.getSteps().size(),
                    execMs);
            String script = exec.getScript();
            fewShotService.trySave(datasourceId, sessionId, question, script, finalStep.getRowCount());
            try {
                String relationSql = lastSelectStatement(guard.getStatements());
                relationUsageService.recordUsage(
                        datasourceId,
                        sessionId,
                        Text2SqlService.questionHash(question),
                        StringUtils.hasText(relationSql) ? relationSql : script,
                        new UsageContext(true, finalStep.getRowCount(), false, false));
            } catch (Exception ex) {
                log.warn("[Sidekick][text2sql] relation usage record skipped: {}", ex.getMessage());
            }
            Text2SqlResult ok = new Text2SqlResult();
            ok.setQuestion(question);
            ok.setSql(script);
            ok.setRawSql(rawSql);
            ok.setColumns(finalStep.getColumns());
            ok.setRows(finalStep.getRows());
            ok.setRowCount(finalStep.getRowCount());
            ok.setTruncated(finalStep.isTruncated());
            ok.setReasoningSteps(trace.steps());
            ok.setSuccess(true);
            ok.setScriptMode(true);
            ok.setScriptSteps(exec.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
            ok.setElapsedMs(System.currentTimeMillis() - pipelineStart);
            return ExecutionPass.success(ok);
        }

        String err = exec.getError() == null ? "执行失败" : exec.getError();
        trace.emit("执行", "失败: " + err, execMs);
        if (!retried) {
            return ExecutionPass.retry(guard.getNormalizedScript(), err, true);
        }
        Text2SqlResult fail = Text2SqlResult.fail(question, err, trace.steps(),
                System.currentTimeMillis() - pipelineStart);
        fail.setSql(guard.getNormalizedScript());
        fail.setRawSql(rawSql);
        fail.setScriptMode(true);
        if (exec.getSteps() != null) {
            fail.setScriptSteps(exec.getSteps().stream().map(ScriptStepResult::toMap).collect(Collectors.toList()));
        }
        return ExecutionPass.failed(fail);
    }

    /** 脚本只取最后一条 SELECT/WITH，中间临时表 DDL 不计入业务关系。 */
    static String lastSelectStatement(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return null;
        }
        String last = null;
        for (String statement : statements) {
            if (!StringUtils.hasText(statement)) {
                continue;
            }
            String text = statement.trim();
            String upper = text.toUpperCase(Locale.ROOT);
            if (upper.startsWith("SELECT") || upper.startsWith("WITH")) {
                last = text;
            }
        }
        return last;
    }
}
