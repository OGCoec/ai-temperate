package com.example.temperate.service.user.apichat.diagnostic;

/**
 * 该枚举是来稳定分类上游流协议错误，使日志能够区分终止帧缺失、顺序错误、字段类型错误和传输解码错误。
 */
public enum ApiChatProtocolViolation {
    MALFORMED_JSON,
    NON_OBJECT_JSON,
    REQUIRED_FIELD_MISSING,
    INVALID_FIELD_TYPE,
    INVALID_CHOICES,
    UNSUPPORTED_CHOICE_INDEX,
    INVALID_TOOL_CALLS,
    INVALID_USAGE,
    ARITHMETIC_OVERFLOW,
    DATA_AFTER_DONE,
    DATA_AFTER_USAGE,
    DUPLICATE_USAGE,
    DONE_WITHOUT_USAGE,
    STREAM_ENDED_WITHOUT_DONE,
    NON_SSE_CONTENT_TYPE,
    INVALID_SSE_BODY,
    UPSTREAM_REJECTED,
    UNKNOWN
}
