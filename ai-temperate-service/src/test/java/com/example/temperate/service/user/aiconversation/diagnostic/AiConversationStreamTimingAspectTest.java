package com.example.temperate.service.user.aiconversation.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 验证时序切面只惰性包装返回的 Flux，不创建内部订阅或改变信号顺序。
 */
final class AiConversationStreamTimingAspectTest {

    @Test
    void wrapsAnnotatedFluxWithoutSubscribingInternally() {
        AtomicInteger subscriptions = new AtomicInteger();
        RecordingDiagnosticService diagnostics =
                new RecordingDiagnosticService();
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(
                new AnnotatedStreamingTarget(subscriptions));
        proxyFactory.addAspect(new AiConversationStreamTimingAspect(diagnostics));
        StreamingTarget proxy = proxyFactory.getProxy();

        Flux<String> result = proxy.stream();

        assertThat(subscriptions).hasValue(0);
        assertThat(diagnostics.lifecycleWraps).hasValue(1);
        StepVerifier.create(result)
                .expectNext("first", "second")
                .verifyComplete();
        assertThat(subscriptions).hasValue(1);
    }

    private interface StreamingTarget {
        Flux<String> stream();
    }

    private static final class AnnotatedStreamingTarget
            implements StreamingTarget {
        private final AtomicInteger subscriptions;

        private AnnotatedStreamingTarget(AtomicInteger subscriptions) {
            this.subscriptions = subscriptions;
        }

        @Override
        @AiConversationStreamTiming
        public Flux<String> stream() {
            return Flux.defer(() -> {
                subscriptions.incrementAndGet();
                return Flux.just("first", "second");
            });
        }
    }

    private static final class RecordingDiagnosticService
            implements AiConversationStreamTimingDiagnosticService {
        private final AtomicInteger lifecycleWraps = new AtomicInteger();

        @Override
        public <T> Flux<T> withSession(
                Flux<T> source,
                AiConversationStreamTimingContext context) {
            return source;
        }

        @Override
        public <T> Flux<T> observeLifecycle(Flux<T> source) {
            lifecycleWraps.incrementAndGet();
            return source;
        }

        @Override
        public <T> Flux<T> observeBoundary(
                Flux<T> source,
                AiConversationStreamTimingBoundary boundary,
                ToIntFunction<T> textCharacters) {
            return source;
        }
    }
}
