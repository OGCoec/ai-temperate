package com.example.temperate.web.user.membership.payment.callback;

import java.util.Objects;

/**
 * 该异常是来区分模拟回调的测试密钥、传输语法和媒体类型错误，使纯文本处理器返回精确 HTTP 状态而不泄露字段细节。
 */
public final class SimulatedPaymentCallbackTransportException
        extends RuntimeException {

    public enum Kind {
        UNAUTHORIZED,
        BAD_REQUEST,
        UNSUPPORTED_MEDIA_TYPE
    }

    private final Kind kind;

    public SimulatedPaymentCallbackTransportException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
