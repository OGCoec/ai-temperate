package com.example.temperate.service.humanverification.logging.aspect;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.humanverification.HumanVerificationCommand;
import com.example.temperate.service.humanverification.HumanVerificationService;
import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.registration.verification.delivery.logging.DebugLogCapture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.aop.support.AopUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * 验证管理员 hCaptcha AOP 只记录调用边界与稳定异常分类，并保持冷 Mono、JDK 代理和 Reactor Context 语义。
 */
class HcaptchaVerificationLoggingAspectTest {

    private static final HumanVerificationCommand SENSITIVE_COMMAND =
            HumanVerificationCommand.hcaptcha(
                    "one-time-hcaptcha-token",
                    "203.0.113.10",
                    "sensitive-challenge");

    @Test
    void remainsLazyAndLogsEachSubscriptionExactlyOnceThroughJdkProxy() {
        AtomicInteger subscriptions = new AtomicInteger();
        HumanVerificationService service = proxy(
                HumanVerificationType.HCAPTCHA,
                Mono.defer(() -> {
                            subscriptions.incrementAndGet();
                            return Mono.<Void>empty();
                        })
                        .subscribeOn(Schedulers.boundedElastic()));

        assertThat(AopUtils.isJdkDynamicProxy(service)).isTrue();
        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaVerificationLoggingAspect.class)) {
            Mono<Void> verification = service.verify(SENSITIVE_COMMAND);

            assertThat(subscriptions).hasValue(0);
            assertThat(logs.messages()).isEmpty();

            StepVerifier.create(verification.contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            "trace-aop")))
                    .verifyComplete();
            StepVerifier.create(verification.contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            "trace-aop")))
                    .verifyComplete();

            assertThat(subscriptions).hasValue(2);
            assertEventCount(logs, "admin_hcaptcha_verification_started", 2);
            assertEventCount(logs, "admin_hcaptcha_verification_completed", 2);
            assertThat(logs.joinedMessages())
                    .contains("traceId=trace-aop")
                    .contains("outcome=succeeded")
                    .contains("implementation=FinalTestHumanVerificationService")
                    .doesNotContain("one-time-hcaptcha-token")
                    .doesNotContain("203.0.113.10")
                    .doesNotContain("sensitive-challenge");
        }
    }

    @Test
    void classifiesControlledAndUnexpectedFailuresWithoutMessages() {
        assertFailure(
                new AdminException(
                        AdminErrorCode.HCAPTCHA_REJECTED,
                        "rejected one-time-hcaptcha-token"),
                "outcome=rejected",
                "adminCode=HCAPTCHA_REJECTED",
                "exceptionClass=AdminException");
        assertFailure(
                new AdminException(
                        AdminErrorCode.HCAPTCHA_UNAVAILABLE,
                        "unavailable one-time-hcaptcha-token"),
                "outcome=unavailable",
                "adminCode=HCAPTCHA_UNAVAILABLE",
                "exceptionClass=AdminException");
        assertFailure(
                new IllegalStateException("unexpected one-time-hcaptcha-token"),
                "outcome=failed",
                "adminCode=unavailable",
                "exceptionClass=IllegalStateException");
    }

    @Test
    void recordsCancellationOnce() {
        HumanVerificationService service =
                proxy(HumanVerificationType.HCAPTCHA, Mono.never());

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaVerificationLoggingAspect.class)) {
            Disposable subscription = service.verify(SENSITIVE_COMMAND)
                    .contextWrite(context -> context.put(
                            HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                            "trace-cancel"))
                    .subscribe();
            subscription.dispose();

            assertEventCount(logs, "admin_hcaptcha_verification_started", 1);
            assertEventCount(logs, "admin_hcaptcha_verification_completed", 1);
            assertThat(logs.joinedMessages())
                    .contains("traceId=trace-cancel")
                    .contains("outcome=cancelled");
        }
    }

    @Test
    void ignoresTurnstileImplementations() {
        HumanVerificationService service =
                proxy(HumanVerificationType.TURNSTILE, Mono.empty());

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaVerificationLoggingAspect.class)) {
            StepVerifier.create(service.verify(SENSITIVE_COMMAND)).verifyComplete();
            assertThat(logs.messages()).isEmpty();
        }
    }

    private static void assertFailure(
            RuntimeException failure,
            String outcome,
            String adminCode,
            String exceptionClass) {
        HumanVerificationService service =
                proxy(HumanVerificationType.HCAPTCHA, Mono.error(failure));

        try (DebugLogCapture logs =
                DebugLogCapture.start(HcaptchaVerificationLoggingAspect.class)) {
            StepVerifier.create(service.verify(SENSITIVE_COMMAND)
                            .contextWrite(context -> context.put(
                                    HumanVerificationService.TRACE_ID_CONTEXT_KEY,
                                    "trace-failure")))
                    .expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
                    .verify();

            assertEventCount(logs, "admin_hcaptcha_verification_started", 1);
            assertEventCount(logs, "admin_hcaptcha_verification_completed", 1);
            assertThat(logs.joinedMessages())
                    .contains("traceId=trace-failure")
                    .contains(outcome)
                    .contains(adminCode)
                    .contains(exceptionClass)
                    .doesNotContain(failure.getMessage())
                    .doesNotContain("one-time-hcaptcha-token")
                    .doesNotContain("203.0.113.10")
                    .doesNotContain("sensitive-challenge");
        }
    }

    private static HumanVerificationService proxy(
            HumanVerificationType type,
            Mono<Void> result) {
        AspectJProxyFactory factory = new AspectJProxyFactory();
        factory.setTarget(new FinalTestHumanVerificationService(type, result));
        factory.setInterfaces(HumanVerificationService.class);
        factory.setProxyTargetClass(false);
        factory.addAspect(new HcaptchaVerificationLoggingAspect());
        return factory.getProxy();
    }

    private static void assertEventCount(
            DebugLogCapture logs,
            String event,
            long expectedCount) {
        assertThat(logs.messages().stream()
                        .filter(message -> message.contains("event=" + event))
                        .count())
                .isEqualTo(expectedCount);
    }

    /**
     * 模拟项目中的 final 策略实现，确保测试覆盖真实的接口代理而不依赖 CGLIB 继承。
     */
    private static final class FinalTestHumanVerificationService
            implements HumanVerificationService {

        private final HumanVerificationType type;
        private final Mono<Void> result;

        private FinalTestHumanVerificationService(
                HumanVerificationType type,
                Mono<Void> result) {
            this.type = type;
            this.result = result;
        }

        @Override
        public HumanVerificationType type() {
            return type;
        }

        @Override
        public Mono<Void> verify(HumanVerificationCommand command) {
            return result;
        }
    }
}
