package com.example.temperate.service.user.apiresponse.diagnostic;

/**
 * 该枚举是来对 Responses 失败发生位置做固定低基数分类，日志不得使用异常消息或客户端字段充当阶段。
 */
public enum ApiResponseFailureStage {
    VALIDATION,
    RESERVATION,
    UPSTREAM,
    PROTOCOL_PARSE,
    BUSINESS_GATE,
    CONTROLLER_GATE,
    MVC_BODY,
    SERVLET_ASYNC,
    ERROR_MAPPING,
    UNKNOWN
}
