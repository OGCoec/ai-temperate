package com.example.temperate.service.user.apichat.diagnostic;

import java.util.Objects;

/**
 * 该内部异常是来把安全的上游传输分类放入受控异常因果链，禁止保留第三方异常对象或原始错误消息。
 */
public final class ApiChatUpstreamFailureException extends RuntimeException {

    private final ApiChatUpstreamFailure failure;

    public ApiChatUpstreamFailureException(ApiChatUpstreamFailure failure) {
        super(Objects.requireNonNull(failure).name());
        this.failure = failure;
    }

    public ApiChatUpstreamFailure failure() {
        return failure;
    }
}
