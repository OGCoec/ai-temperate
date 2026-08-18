package com.example.temperate.service.user.apiresponse.diagnostic;

/**
 * 该不可变调用对象是来关联一次 AOP 进入、阶段耗时和请求级诊断会话，并标识谁有权输出最终摘要。
 */
public record ApiResponseDiagnosticInvocation(
        ApiResponseDiagnosticSession session,
        ApiResponseDiagnosticStage stage,
        long startedNanos,
        boolean owner) {
}
