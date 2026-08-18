package com.example.temperate.service.user.apiresponse.diagnostic;

/**
 * 该枚举是来标识 Responses SSE 从 8317 解码到 Spring MVC Body 写出的五个不可混淆边界。
 */
public enum ApiResponseDiagnosticBoundary {
    UPSTREAM_RAW,
    AFTER_PROTOCOL_PARSE,
    AFTER_BUSINESS_GATE,
    CONTROLLER_GATE_RECEIVED,
    MVC_BODY_EMIT
}
