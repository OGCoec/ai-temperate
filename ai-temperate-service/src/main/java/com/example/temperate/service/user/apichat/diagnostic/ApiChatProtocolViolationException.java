package com.example.temperate.service.user.apichat.diagnostic;

import java.util.Objects;

/**
 * 该内部异常是来把安全的协议分类保留在异常因果链中，不携带上游响应正文、模型输出或凭据。
 */
public final class ApiChatProtocolViolationException extends RuntimeException {

    private final ApiChatProtocolViolation violation;

    public ApiChatProtocolViolationException(ApiChatProtocolViolation violation) {
        super(Objects.requireNonNull(violation).name());
        this.violation = violation;
    }

    public ApiChatProtocolViolation violation() {
        return violation;
    }
}
