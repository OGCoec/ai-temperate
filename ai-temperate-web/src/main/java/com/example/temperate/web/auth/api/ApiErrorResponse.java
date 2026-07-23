package com.example.temperate.web.auth.api;

import java.time.Instant;

/**
 * 对外 HTTP API 的统一错误响应载体。
 *
 * <p>用途：以稳定错误码、面向客户端的消息和服务端时间戳表达失败，不承载异常堆栈、Token 或内部实现细节。</p>
 */
public record ApiErrorResponse(
        String code,
        String message,
        Instant timestamp) {
}
