package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 验证生命周期 AOP 对同步方法和 Reactor 返回值进行计时，但不会创建内部订阅。
 */
final class AiConversationLifecycleTimingAspectTest {

    @Test
    void recordsSynchronousEntryAndCompletion() {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AspectJProxyFactory factory = new AspectJProxyFactory(new AnnotatedTarget());
        factory.addAspect(new AiConversationLifecycleTimingAspect(diagnostics));
        TimedTarget proxy = factory.getProxy();

        assertThat(proxy.call()).isEqualTo("ok");
        assertThat(diagnostics.phases)
                .containsExactly("SETTLEMENT_REFUND_ENTERED", "SETTLEMENT_REFUND_COMPLETED");
    }

    @Test
    void wrapsFluxWithoutSubscribingInternally() {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AtomicInteger subscriptions = new AtomicInteger();
        AspectJProxyFactory factory = new AspectJProxyFactory(
                new AnnotatedTarget(subscriptions));
        factory.addAspect(new AiConversationLifecycleTimingAspect(diagnostics));
        TimedTarget proxy = factory.getProxy();

        Flux<String> result = proxy.stream();

        assertThat(subscriptions).hasValue(0);
        StepVerifier.create(result).expectNext("chunk").verifyComplete();
        assertThat(subscriptions).hasValue(1);
        assertThat(diagnostics.phases)
                .containsExactly("UPSTREAM_MODEL_ENTERED", "UPSTREAM_MODEL_COMPLETED");
    }

    @Test
    void classifiesControlledFailureWithoutLoggingItsMessage() {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AspectJProxyFactory factory = new AspectJProxyFactory(new AnnotatedTarget());
        factory.addAspect(new AiConversationLifecycleTimingAspect(diagnostics));
        TimedTarget proxy = factory.getProxy();

        assertThatThrownBy(proxy::failControlled)
                .isInstanceOf(AiConversationException.class);

        assertThat(diagnostics.phases)
                .containsExactly("SETTLEMENT_REFUND_ENTERED", "SETTLEMENT_REFUND_FAILED");
        AiConversationLifecycleEvent failure = diagnostics.events.get(1);
        assertThat(failure.outcome()).isEqualTo("CONTROLLED_FAILURE");
        assertThat(failure.failureCode())
                .isEqualTo(AiConversationErrorCode.AI_UPSTREAM_TIMEOUT.name());
        assertThat(failure.toString()).doesNotContain("PRIVATE_UPSTREAM_MESSAGE");
    }

    private interface TimedTarget {
        String call();

        Flux<String> stream();

        void failControlled();
    }

    private static final class AnnotatedTarget implements TimedTarget {
        private final AtomicInteger subscriptions;

        private AnnotatedTarget() {
            this(new AtomicInteger());
        }

        private AnnotatedTarget(AtomicInteger subscriptions) {
            this.subscriptions = subscriptions;
        }

        @Override
        @AiConversationLifecycleTimed(stage = "SETTLEMENT_REFUND")
        public String call() {
            return "ok";
        }

        @Override
        @AiConversationLifecycleTimed(stage = "UPSTREAM_MODEL")
        public Flux<String> stream() {
            return Flux.defer(() -> {
                subscriptions.incrementAndGet();
                return Flux.just("chunk");
            });
        }

        @Override
        @AiConversationLifecycleTimed(stage = "SETTLEMENT_REFUND")
        public void failControlled() {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_TIMEOUT,
                    "PRIVATE_UPSTREAM_MESSAGE",
                    true);
        }
    }

    private static final class RecordingDiagnostics
            implements AiConversationLifecycleDiagnosticService {
        private final List<String> phases = new ArrayList<>();
        private final List<AiConversationLifecycleEvent> events =
                new ArrayList<>();

        @Override
        public void record(
                AiConversationLifecycleTraceContext context,
                String phase,
                AiConversationLifecycleEvent details) {
            phases.add(phase);
            events.add(details);
        }

        @Override
        public AiConversationLifecycleTraceContext currentContext() {
            return AiConversationLifecycleTraceContext.unavailable();
        }

        @Override
        public <T> T withContext(
                AiConversationLifecycleTraceContext context,
                Supplier<T> action) {
            return action.get();
        }

        @Override
        public void withContext(
                AiConversationLifecycleTraceContext context,
                Runnable action) {
            action.run();
        }
    }
}
