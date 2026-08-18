package com.example.temperate.service.user.apiresponse.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apiresponse.diagnostic.impl.ApiResponseStreamDiagnosticServiceImpl;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import reactor.core.publisher.Flux;

/**
 * 该测试是来验证 Responses AOP 只做惰性旁路观察，不增加订阅并且不包装同步或异步业务异常。
 */
final class ApiResponseStreamDiagnosticAspectTest {

    @Test
    void keepsFluxLazyAndSubscribesExactlyOnce() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(true);
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(properties, System::nanoTime);
        Target target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ApiResponseStreamDiagnosticAspect(diagnostics));
        Target proxy = factory.getProxy();

        Flux<String> result = proxy.stream();

        assertThat(target.subscriptions).isZero();
        assertThat(result.collectList().block()).containsExactly("a", "b");
        assertThat(target.subscriptions).isEqualTo(1);
    }

    @Test
    void propagatesTheSameSynchronousAndAsynchronousFailures() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setEnabled(true);
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiResponseStreamDiagnosticServiceImpl diagnostics =
                new ApiResponseStreamDiagnosticServiceImpl(properties, System::nanoTime);
        Target target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ApiResponseStreamDiagnosticAspect(diagnostics));
        Target proxy = factory.getProxy();
        RuntimeException synchronous = new IllegalArgumentException("sync-secret");
        RuntimeException asynchronous = new IllegalStateException("async-secret");
        target.synchronousFailure = synchronous;
        target.asynchronousFailure = asynchronous;

        assertThatThrownBy(proxy::failSynchronously).isSameAs(synchronous);
        assertThatThrownBy(() -> proxy.failAsynchronously().blockLast())
                .isSameAs(asynchronous);
    }

    static class Target {
        private int subscriptions;
        private RuntimeException synchronousFailure;
        private RuntimeException asynchronousFailure;

        @ApiResponseStreamDiagnostic(stage = ApiResponseDiagnosticStage.RESPONSE_SERVICE)
        public Flux<String> stream() {
            return Flux.defer(() -> {
                subscriptions++;
                return Flux.just("a", "b");
            });
        }

        @ApiResponseStreamDiagnostic(stage = ApiResponseDiagnosticStage.RESPONSE_SERVICE)
        public Flux<String> failSynchronously() {
            throw synchronousFailure;
        }

        @ApiResponseStreamDiagnostic(stage = ApiResponseDiagnosticStage.RESPONSE_SERVICE)
        public Flux<String> failAsynchronously() {
            return Flux.error(asynchronousFailure);
        }
    }
}
