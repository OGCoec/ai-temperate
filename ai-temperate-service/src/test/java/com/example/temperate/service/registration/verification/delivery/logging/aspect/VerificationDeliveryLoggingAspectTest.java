package com.example.temperate.service.registration.verification.delivery.logging.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryLogContext;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import com.example.temperate.service.registration.verification.delivery.logging.annotation.VerificationDeliveryLogged;
import com.example.temperate.service.registration.verification.delivery.util.gmail.GmailApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioVerifySmsUtil;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.impl.AliyunSmsSixDigitVerificationCodeServiceImpl;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 验证验证码供应商日志只由接口注解驱动的 AOP 输出，并在 JDK 代理和 Reactor 异步链路中保持安全关联。
 */
class VerificationDeliveryLoggingAspectTest {

    private static final VerificationDeliveryRequest SENSITIVE_REQUEST =
            new VerificationDeliveryRequest("alice@example.test", "012345");

    @Test
    void sendCodeInterfaceMethodCarriesRuntimeMethodAnnotation() throws Exception {
        Method method = SixDigitVerificationCodeService.class.getMethod(
                "sendCode", VerificationDeliveryRequest.class);
        VerificationDeliveryLogged annotation =
                method.getAnnotation(VerificationDeliveryLogged.class);
        Retention retention = VerificationDeliveryLogged.class.getAnnotation(Retention.class);
        Target target = VerificationDeliveryLogged.class.getAnnotation(Target.class);

        assertThat(annotation).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(target.value()).containsExactly(ElementType.METHOD);
        assertThat(SixDigitVerificationCodeService.class
                        .getMethod("type")
                        .getAnnotation(VerificationDeliveryLogged.class))
                .isNull();
        assertThat(SixDigitVerificationCodeService.class
                        .getMethod("verifyCode", RegistrationVerifyCodeCommand.class)
                        .getAnnotation(VerificationDeliveryLogged.class))
                .isNull();
        assertThat(FinalTestDeliveryService.class
                        .getMethod("sendCode", VerificationDeliveryRequest.class)
                        .getAnnotation(VerificationDeliveryLogged.class))
                .isNull();
    }

    @Test
    void providerMetadataRejectsUnsafeOrOversizedDiagnosticStrings() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        403,
                        "unsafe\n012345",
                        "failed",
                        false,
                        "x".repeat(129),
                        "ODataError");

        assertThat(metadata.providerCode()).isEqualTo("unavailable");
        assertThat(metadata.requestId()).isEqualTo("unavailable");
        assertThat(metadata.providerStatus()).isEqualTo("failed");
        assertThat(metadata.exceptionClass()).isEqualTo("ODataError");
    }

    @Test
    void providerAdaptersDoNotOwnSlf4jLoggerFields() {
        Stream<Class<?>> providerTypes = Stream.of(
                GmailApiMailUtil.class,
                MicrosoftGraphApiMailUtil.class,
                TwilioVerifySmsUtil.class,
                AliyunSmsSixDigitVerificationCodeServiceImpl.class);

        assertThat(providerTypes
                        .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
                        .filter(field -> Logger.class.isAssignableFrom(field.getType())))
                .isEmpty();
    }

    @Test
    void jdkProxyLogsSelectedSafeResponseAndCompletedOnce() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        null,
                        null,
                        "accepted",
                        true,
                        null,
                        null);
        SixDigitVerificationCodeService service = proxy(Mono.fromCallable(() ->
                        new VerificationDeliveryResult(
                                VerificationChannel.EMAIL,
                                "microsoft_graph",
                                null,
                                Instant.EPOCH,
                                metadata))
                .subscribeOn(Schedulers.boundedElastic()));

        assertThat(AopUtils.isJdkDynamicProxy(service)).isTrue();
        try (DebugLogCapture logs = DebugLogCapture.start(
                VerificationDeliveryLoggingAspect.class)) {
            VerificationDeliveryResult result = logContext()
                    .propagate(service.sendCode(SENSITIVE_REQUEST))
                    .block();

            assertThat(result).isNotNull();
            String output = logs.joinedMessages();
            assertThat(output)
                    .contains("event=verification_delivery_provider_selected")
                    .contains("provider=microsoft_graph")
                    .contains("impl=FinalTestDeliveryService")
                    .contains("event=verification_delivery_provider_response")
                    .contains("outcome=accepted")
                    .contains("httpStatus=unavailable")
                    .contains("providerCode=unavailable")
                    .contains("providerStatus=accepted")
                    .contains("providerSuccess=true")
                    .contains("requestId=unavailable")
                    .contains("event=verification_delivery_provider_completed")
                    .contains("traceId=trace-aop")
                    .contains("messageId=message-aop")
                    .doesNotContain("alice@example.test")
                    .doesNotContain("012345");
            assertEventCount(logs, "verification_delivery_provider_selected", 1);
            assertEventCount(logs, "verification_delivery_provider_response", 1);
            assertEventCount(logs, "verification_delivery_provider_completed", 1);
        }
    }

    @Test
    void failureLogsSafeMetadataWithoutThrowableMessageOrStackTrace() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        403,
                        "Authorization_RequestDenied",
                        "failed",
                        false,
                        "graph-request-403",
                        "MicrosoftGraphHttpResponse",
                        Operation.SEND_MAIL,
                        Endpoint.ME_SEND_MAIL,
                        FailureStage.PROVIDER_API,
                        FailureCategory.PERMISSION_DENIED,
                        FailureHint.GRAPH_PERMISSION_OR_CONSENT_MISSING,
                        RecommendedAction.VERIFY_MAIL_SEND_CONSENT,
                        false,
                        false,
                        null);
        VerificationDeliveryException failure = new VerificationDeliveryException(
                false,
                "microsoft_graph",
                "microsoft_graph_http_error",
                metadata,
                new IllegalStateException(
                        "raw body contains alice@example.test and 012345"));
        SixDigitVerificationCodeService service = proxy(Mono.error(failure));

        try (DebugLogCapture logs = DebugLogCapture.start(
                VerificationDeliveryLoggingAspect.class)) {
            assertThatThrownBy(() -> logContext()
                            .propagate(service.sendCode(SENSITIVE_REQUEST))
                            .block())
                    .isInstanceOf(VerificationDeliveryException.class);

            String output = logs.joinedMessages();
            assertThat(output)
                    .contains("event=verification_delivery_provider_response")
                    .contains("outcome=failed")
                    .contains("httpStatus=403")
                    .contains("providerCode=Authorization_RequestDenied")
                    .contains("operation=send_mail")
                    .contains("endpoint=me_send_mail")
                    .contains("failureStage=provider_api")
                    .contains("failureCategory=permission_denied")
                    .contains("failureHint=graph_permission_or_consent_missing")
                    .contains("recommendedAction=verify_mail_send_consent")
                    .contains("explicitFrom=false")
                    .contains("authRefreshAttempted=false")
                    .contains("retryAfterSeconds=unavailable")
                    .contains("safeReason=microsoft_graph_http_error")
                    .contains("retryable=false")
                    .contains("exceptionClass=MicrosoftGraphHttpResponse")
                    .contains("event=verification_delivery_provider_completed")
                    .doesNotContain("raw body contains")
                    .doesNotContain("alice@example.test")
                    .doesNotContain("012345");
            assertEventCount(logs, "verification_delivery_provider_selected", 1);
            assertEventCount(logs, "verification_delivery_provider_response", 1);
            assertEventCount(logs, "verification_delivery_provider_completed", 1);
        }
    }

    @Test
    void futureCompletionKeepsRabbitCorrelationContext() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        null, null, "accepted", true, null, null);
        SixDigitVerificationCodeService service = proxy(Mono.defer(() ->
                Mono.fromFuture(CompletableFuture.supplyAsync(() ->
                        new VerificationDeliveryResult(
                                VerificationChannel.EMAIL,
                                "microsoft_graph",
                                null,
                                Instant.EPOCH,
                                metadata)))));

        try (DebugLogCapture logs = DebugLogCapture.start(
                VerificationDeliveryLoggingAspect.class)) {
            VerificationDeliveryResult result = logContext()
                    .propagate(service.sendCode(SENSITIVE_REQUEST))
                    .block();

            assertThat(result).isNotNull();
            assertThat(logs.joinedMessages())
                    .contains("traceId=trace-aop")
                    .contains("messageId=message-aop")
                    .contains("attemptNo=1")
                    .contains("providerStatus=accepted")
                    .doesNotContain("alice@example.test")
                    .doesNotContain("012345");
        }
    }

    @Test
    void emptyMonoLogsControlledFailureWithoutChangingEmptyCompletion() {
        SixDigitVerificationCodeService service = proxy(Mono.empty());

        try (DebugLogCapture logs = DebugLogCapture.start(
                VerificationDeliveryLoggingAspect.class)) {
            VerificationDeliveryResult result = logContext()
                    .propagate(service.sendCode(SENSITIVE_REQUEST))
                    .block();

            assertThat(result).isNull();
            assertThat(logs.joinedMessages())
                    .contains("event=verification_delivery_provider_response")
                    .contains("outcome=unknown")
                    .contains("safeReason=verification_delivery_empty_result")
                    .contains("retryable=false")
                    .contains("event=verification_delivery_provider_completed");
        }
    }

    private static SixDigitVerificationCodeService proxy(
            Mono<VerificationDeliveryResult> result) {
        AspectJProxyFactory factory = new AspectJProxyFactory();
        factory.setTarget(new FinalTestDeliveryService(result));
        factory.setInterfaces(SixDigitVerificationCodeService.class);
        factory.setProxyTargetClass(false);
        factory.addAspect(new VerificationDeliveryLoggingAspect());
        return factory.getProxy();
    }

    private static VerificationDeliveryLogContext logContext() {
        return new VerificationDeliveryLogContext(
                "trace-aop",
                "message-aop",
                "registration",
                "email",
                "registration",
                1,
                6);
    }

    private static void assertEventCount(
            DebugLogCapture logs, String event, long expectedCount) {
        assertThat(logs.messages().stream()
                        .filter(message -> message.contains("event=" + event))
                        .count())
                .isEqualTo(expectedCount);
    }

    /**
     * 模拟项目使用的 final Service 实现，确保切面测试覆盖真实的 JDK 接口代理方式。
     */
    private static final class FinalTestDeliveryService
            implements SixDigitVerificationCodeService {

        private final Mono<VerificationDeliveryResult> result;

        private FinalTestDeliveryService(Mono<VerificationDeliveryResult> result) {
            this.result = result;
        }

        @Override
        public VerificationProvider type() {
            return VerificationProvider.MICROSOFT_GRAPH;
        }

        @Override
        public Mono<VerificationDeliveryResult> sendCode(
                VerificationDeliveryRequest request) {
            return result;
        }

        @Override
        public RegistrationStatusResult verifyCode(
                RegistrationVerifyCodeCommand command) {
            throw new UnsupportedOperationException("This test only covers delivery logging.");
        }
    }
}
