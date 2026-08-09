package com.example.temperate.service.registration.verification.delivery.logging.aspect;

import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryLogContext;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.annotation.VerificationDeliveryLogged;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import java.lang.reflect.Method;
import java.util.Locale;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import reactor.core.publisher.Mono;

/**
 * 统一记录验证码供应商的选择、安全响应和完成结果，并通过 Reactor Context 关联 RabbitMQ 消息链路。
 *
 * <p>切面只读取接口注解、受控投递结果和受控异常元数据，不读取请求参数、异常消息或第三方原始响应；
 * RabbitMQ 的发布、重试与 ACK/NACK 状态仍由消息组件原位记录。</p>
 */
@Aspect
@Component
public final class VerificationDeliveryLoggingAspect {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(VerificationDeliveryLoggingAspect.class);
    private static final Method LOGGED_INTERFACE_METHOD = resolveLoggedInterfaceMethod();

    /**
     * 在 Service 返回的 Mono 被实际订阅时开始计时，使线程切换后的结果仍使用消息消费者写入的关联上下文。
     */
    @Around(
            "execution(reactor.core.publisher.Mono "
                    + "com.example.temperate.service.registration.verification.service."
                    + "SixDigitVerificationCodeService.sendCode(..))")
    public Object logProviderDelivery(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!LOGGED_INTERFACE_METHOD.isAnnotationPresent(VerificationDeliveryLogged.class)) {
            return joinPoint.proceed();
        }

        Object result = joinPoint.proceed();
        if (!(result instanceof Mono<?> operation)
                || !(joinPoint.getTarget() instanceof SixDigitVerificationCodeService service)) {
            return result;
        }

        VerificationProvider providerType = service.type();
        String provider = tag(providerType);
        String implementation = VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(
                ClassUtils.getUserClass(joinPoint.getTarget()).getSimpleName());

        @SuppressWarnings("unchecked")
        Mono<VerificationDeliveryResult> delivery =
                (Mono<VerificationDeliveryResult>) operation;
        return Mono.deferContextual(contextView -> {
            VerificationDeliveryLogContext context =
                    VerificationDeliveryLogContext.current(contextView);
            long startedAtNanos = System.nanoTime();
            logSelected(provider, implementation, context);
            return delivery
                    .doOnSuccess(deliveryResult -> {
                        if (deliveryResult == null) {
                            logEmpty(provider, startedAtNanos, context);
                            return;
                        }
                        logAccepted(provider, deliveryResult, startedAtNanos, context);
                    })
                    .doOnError(failure ->
                            logFailed(provider, failure, startedAtNanos, context));
        });
    }

    private static void logSelected(
            String provider,
            String implementation,
            VerificationDeliveryLogContext context) {
        LOGGER.debug(
                "event=verification_delivery_provider_selected provider={} impl={} "
                        + "traceId={} messageId={} flow={} channel={} deliveryMethod={} "
                        + "purpose={} attemptNo={} maxAttempts={}",
                provider,
                implementation,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logAccepted(
            String provider,
            VerificationDeliveryResult result,
            long startedAtNanos,
            VerificationDeliveryLogContext context) {
        VerificationDeliveryProviderMetadata metadata = result.metadata();
        String providerMessageId =
                VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(
                        result.providerMessageId());
        logResponse(
                provider,
                "accepted",
                metadata,
                providerMessageId,
                "accepted",
                false,
                context);
        logCompleted(
                provider,
                "accepted",
                elapsedMillis(startedAtNanos),
                providerMessageId,
                "accepted",
                false,
                context);
    }

    private static void logFailed(
            String provider,
            Throwable failure,
            long startedAtNanos,
            VerificationDeliveryLogContext context) {
        VerificationDeliveryException deliveryFailure = findDeliveryFailure(failure);
        VerificationDeliveryProviderMetadata metadata;
        String safeReason;
        boolean retryable;
        String outcome;
        if (deliveryFailure == null) {
            metadata = metadataForUnexpectedFailure(failure);
            safeReason = "verification_delivery_outcome_unknown";
            retryable = false;
            outcome = "unknown";
        } else {
            Throwable classifiedFailure = deliveryFailure.getCause() == null
                    ? failure
                    : deliveryFailure.getCause();
            metadata = withFallbackExceptionClass(
                    deliveryFailure.metadata(), classifiedFailure);
            safeReason = VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(
                    deliveryFailure.safeReason());
            retryable = deliveryFailure.retryable();
            outcome = deliveryFailure.outcome().name().toLowerCase(Locale.ROOT);
        }
        logResponse(
                provider,
                outcome,
                metadata,
                "unavailable",
                safeReason,
                retryable,
                context);
        logCompleted(
                provider,
                outcome,
                elapsedMillis(startedAtNanos),
                "unavailable",
                safeReason,
                retryable,
                context);
    }

    private static void logEmpty(
            String provider,
            long startedAtNanos,
            VerificationDeliveryLogContext context) {
        String safeReason = "verification_delivery_empty_result";
        VerificationDeliveryProviderMetadata metadata =
                VerificationDeliveryProviderMetadata.empty();
        logResponse(
                provider,
                "unknown",
                metadata,
                "unavailable",
                safeReason,
                false,
                context);
        logCompleted(
                provider,
                "unknown",
                elapsedMillis(startedAtNanos),
                "unavailable",
                safeReason,
                false,
                context);
    }

    private static void logResponse(
            String provider,
            String outcome,
            VerificationDeliveryProviderMetadata metadata,
            String providerMessageId,
            String safeReason,
            boolean retryable,
            VerificationDeliveryLogContext context) {
        LOGGER.debug(
                "event=verification_delivery_provider_response provider={} outcome={} "
                        + "httpStatus={} providerCode={} providerStatus={} providerSuccess={} "
                        + "operation={} endpoint={} failureStage={} failureCategory={} "
                        + "failureHint={} recommendedAction={} explicitFrom={} "
                        + "authRefreshAttempted={} retryAfterSeconds={} oauthError={} "
                        + "oauthErrorCodes={} oauthFailureReason={} "
                        + "requestId={} exceptionClass={} providerMessageId={} safeReason={} "
                        + "retryable={} traceId={} messageId={} flow={} channel={} deliveryMethod={} "
                        + "purpose={} attemptNo={} maxAttempts={}",
                provider,
                outcome,
                valueOrUnavailable(metadata.httpStatus()),
                metadata.providerCode(),
                metadata.providerStatus(),
                valueOrUnavailable(metadata.providerSuccess()),
                enumValueOrUnavailable(metadata.operation()),
                enumValueOrUnavailable(metadata.endpoint()),
                enumValueOrUnavailable(metadata.failureStage()),
                enumValueOrUnavailable(metadata.failureCategory()),
                enumValueOrUnavailable(metadata.failureHint()),
                enumValueOrUnavailable(metadata.recommendedAction()),
                valueOrUnavailable(metadata.explicitFrom()),
                valueOrUnavailable(metadata.authRefreshAttempted()),
                valueOrUnavailable(metadata.retryAfterSeconds()),
                metadata.oauthError(),
                metadata.oauthErrorCodes(),
                metadata.oauthFailureReason(),
                metadata.requestId(),
                metadata.exceptionClass(),
                providerMessageId,
                safeReason,
                retryable,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static void logCompleted(
            String provider,
            String outcome,
            long durationMs,
            String providerMessageId,
            String safeReason,
            boolean retryable,
            VerificationDeliveryLogContext context) {
        LOGGER.debug(
                "event=verification_delivery_provider_completed provider={} outcome={} "
                        + "durationMs={} providerMessageId={} safeReason={} retryable={} "
                        + "traceId={} messageId={} flow={} channel={} deliveryMethod={} purpose={} "
                        + "attemptNo={} maxAttempts={}",
                provider,
                outcome,
                durationMs,
                providerMessageId,
                safeReason,
                retryable,
                context.traceId(),
                context.messageId(),
                context.flow(),
                context.channel(),
                context.deliveryMethod(),
                context.purpose(),
                context.attemptNo(),
                context.maxAttempts());
    }

    private static VerificationDeliveryProviderMetadata metadataForUnexpectedFailure(
            Throwable failure) {
        return new VerificationDeliveryProviderMetadata(
                null,
                null,
                "failed",
                false,
                null,
                exceptionClass(failure));
    }

    private static VerificationDeliveryProviderMetadata withFallbackExceptionClass(
            VerificationDeliveryProviderMetadata metadata,
            Throwable failure) {
        if (!"unavailable".equals(metadata.exceptionClass())) {
            return metadata;
        }
        return metadata.withFallbackExceptionClass(exceptionClass(failure));
    }

    private static VerificationDeliveryException findDeliveryFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof VerificationDeliveryException deliveryFailure) {
                return deliveryFailure;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private static String exceptionClass(Throwable failure) {
        return failure == null
                ? "unavailable"
                : VerificationDeliveryProviderMetadata.sanitizeDiagnosticValue(
                        failure.getClass().getSimpleName());
    }

    private static Object valueOrUnavailable(Object value) {
        return value == null ? "unavailable" : value;
    }

    private static String enumValueOrUnavailable(Enum<?> value) {
        return value == null
                ? "unavailable"
                : value.name().toLowerCase(Locale.ROOT);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static String tag(VerificationProvider provider) {
        return provider.name().toLowerCase(Locale.ROOT);
    }

    private static Method resolveLoggedInterfaceMethod() {
        try {
            return SixDigitVerificationCodeService.class.getMethod(
                    "sendCode", VerificationDeliveryRequest.class);
        } catch (NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
