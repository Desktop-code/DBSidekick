package com.dbsidekick.text2sql;

/**
 * {@link PromptContextAssembler#assembleStream} 的结果。
 * 检索失败时只带失败结果；成功时带 {@link PromptAssembly}。
 * 请求级对象，不是 Spring Bean。
 */
public final class AssemblyResult {

    private final Text2SqlResult failure;
    private final PromptAssembly assembly;

    private AssemblyResult(Text2SqlResult failure, PromptAssembly assembly) {
        this.failure = failure;
        this.assembly = assembly;
    }

    public static AssemblyResult fail(Text2SqlResult failure) {
        return new AssemblyResult(failure, null);
    }

    public static AssemblyResult ok(PromptAssembly assembly) {
        return new AssemblyResult(null, assembly);
    }

    public boolean failed() {
        return failure != null;
    }

    public Text2SqlResult failure() {
        return failure;
    }

    public PromptAssembly assembly() {
        return assembly;
    }
}
