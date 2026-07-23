package com.example.temperate.service.registration.verification.delivery.logging;

import com.example.temperate.service.registration.verification.delivery.rabbit.VerificationDeliveryMessage;
import java.util.Locale;
import java.util.Objects;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * 保存验证码投递 DEBUG 日志允许使用的最小关联上下文，并负责把它安全地传入 Reactor 链路。
 *
 * <p>该上下文只包含内部关联标识和有限枚举标签，不包含验证码、目标地址、加密 payload 或供应商凭据；
 * 因此统一日志切面可以在 Twilio 的 bounded-elastic 线程和阿里云 Future 回调完成后继续关联同一
 * traceId/messageId，而无需依赖会在线程切换时丢失的 ThreadLocal。</p>
 */
public record VerificationDeliveryLogContext(
        String traceId,
        String messageId,
        String flow,
        String channel,
        String deliveryMethod,
        String purpose,
        int attemptNo,
        int maxAttempts) {

    public VerificationDeliveryLogContext {
        traceId = requireText(traceId, "traceId");
        messageId = requireText(messageId, "messageId");
        flow = requireText(flow, "flow");
        channel = requireText(channel, "channel");
        deliveryMethod = requireText(deliveryMethod, "deliveryMethod");
        purpose = requireText(purpose, "purpose");
        if (attemptNo < 0 || maxAttempts < 0 || attemptNo > maxAttempts) {
            throw new IllegalArgumentException("attemptNo must be within maxAttempts");
        }
    }

    /**
     * 保留旧测试和非 Rabbit 调用的构造方式，投递方式未知时使用有限诊断值而不推测业务数据。
     */
    public VerificationDeliveryLogContext(
            String traceId,
            String messageId,
            String flow,
            String channel,
            String purpose,
            int attemptNo,
            int maxAttempts) {
        this(
                traceId,
                messageId,
                flow,
                channel,
                "unavailable",
                purpose,
                attemptNo,
                maxAttempts);
    }

    public static VerificationDeliveryLogContext from(VerificationDeliveryMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        return new VerificationDeliveryLogContext(
                message.traceId(),
                message.messageId(),
                tag(message.flowKind()),
                tag(message.channel()),
                tag(message.deliveryMethod()),
                tag(message.purpose()),
                message.attemptNo(),
                message.maxAttempts());
    }

    public static VerificationDeliveryLogContext current(ContextView contextView) {
        Objects.requireNonNull(contextView, "contextView must not be null");
        return contextView.getOrDefault(
                VerificationDeliveryLogContext.class,
                new VerificationDeliveryLogContext(
                        "unavailable",
                        "unavailable",
                        "unavailable",
                        "unavailable",
                        "unavailable",
                        "unavailable",
                        0,
                        0));
    }

    /**
     * 在订阅阶段写入上下文，保证异步供应商 Mono 在实际执行时读取到本次消息的关联字段。
     */
    public <T> Mono<T> propagate(Mono<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        return operation.contextWrite(context ->
                context.put(VerificationDeliveryLogContext.class, this));
    }

    private static String tag(Enum<?> value) {
        return Objects.requireNonNull(value, "log context enum must not be null")
                .name()
                .toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
