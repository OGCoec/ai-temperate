package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该枚举是来标识同一 SSE 帧经过的四个观察边界，从而定位帧是在上游、协议解析、业务门禁还是输出准备阶段丢失。
 */
public enum ApiChatDiagnosticBoundary {
    UPSTREAM_RAW,
    AFTER_PROTOCOL_PARSE,
    AFTER_BUSINESS_GATE,
    SSE_EVENT_READY
}
