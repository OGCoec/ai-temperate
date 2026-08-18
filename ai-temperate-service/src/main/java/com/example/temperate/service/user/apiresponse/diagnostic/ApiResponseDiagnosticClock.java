package com.example.temperate.service.user.apiresponse.diagnostic;

/**
 * 该时钟是来为 Responses 流诊断提供可替换的单调纳秒时间源，使窗口、静默和阶段耗时可以稳定测试。
 */
@FunctionalInterface
public interface ApiResponseDiagnosticClock {

    long nanoTime();
}
