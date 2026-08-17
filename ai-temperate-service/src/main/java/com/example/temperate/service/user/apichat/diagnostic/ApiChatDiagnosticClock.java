package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该时钟是来为流式诊断提供单调纳秒时间，生产环境使用系统时钟，测试可精确控制帧间隔。
 */
@FunctionalInterface
public interface ApiChatDiagnosticClock {

    long nanoTime();
}
