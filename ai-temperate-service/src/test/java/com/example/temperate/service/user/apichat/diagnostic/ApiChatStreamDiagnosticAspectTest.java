package com.example.temperate.service.user.apichat.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.diagnostic.impl.ApiChatStreamDiagnosticServiceImpl;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该测试是来验证 API Chat AOP 惰性包装 Flux/Mono，不建立内部订阅，也不会改变信号顺序。
 */
final class ApiChatStreamDiagnosticAspectTest {

    @Test
    void wrapsAnnotatedFluxWithoutChangingValues() {
        ApiKeyProperties properties = new ApiKeyProperties();
        ApiChatStreamDiagnosticService diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(properties, System::nanoTime);
        Target target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ApiChatStreamDiagnosticAspect(diagnostics));
        Target proxy = factory.getProxy();

        assertThat(proxy.stream().collectList().block())
                .containsExactly("a", "b");
        assertThat(target.subscriptions).isEqualTo(1);
    }

    @Test
    void wrapsAnnotatedMonoWithoutChangingItsValue() {
        ApiKeyProperties properties = new ApiKeyProperties();
        ApiChatStreamDiagnosticService diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(properties, System::nanoTime);
        Target target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ApiChatStreamDiagnosticAspect(diagnostics));
        Target proxy = factory.getProxy();

        assertThat(proxy.mono().block()).isEqualTo("json");
        assertThat(target.subscriptions).isEqualTo(1);
    }

    @Test
    void recordsUnsampledFailureAndRethrowsTheOriginalException() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiChatStreamDiagnosticService diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(properties, System::nanoTime);
        Target target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ApiChatStreamDiagnosticAspect(diagnostics));
        Target proxy = factory.getProxy();
        ApiChatException failure = ApiChatException.invalid(
                "Function tool is invalid.", "tools");
        target.failure = failure;

        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-unsampled-failure");
        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> {
                proxy.fail();
            })
                    .isSameAs(failure);

            assertThat(logs.joined())
                    .contains("event=api_chat_stage_failure")
                    .contains("diagnosticSchema=chat-diag-v1")
                    .contains("traceId=trace-unsampled-failure")
                    .contains("stage=COMPLETION_SERVICE")
                    .contains("apiErrorCode=invalid_request")
                    .contains("httpStatus=400")
                    .contains("parameter=tools")
                    .contains("sampled=false")
                    .doesNotContain("Function tool is invalid.");
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    @Test
    void recordsUnsampledAsynchronousFailureWithoutReplacingTheSignal() {
        ApiKeyProperties properties = new ApiKeyProperties();
        properties.getStreamDiagnostics().setSampleRate(0.0d);
        ApiChatStreamDiagnosticService diagnostics =
                new ApiChatStreamDiagnosticServiceImpl(properties, System::nanoTime);
        Target target = new Target();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ApiChatStreamDiagnosticAspect(diagnostics));
        Target proxy = factory.getProxy();
        ApiChatException failure = ApiChatException.invalid(
                "Function tool is invalid.", "tools");
        target.failure = failure;
        String previousTrace = MDC.get("apiChatTraceId");
        MDC.put("apiChatTraceId", "trace-unsampled-async-failure");

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> proxy.streamFailure().blockLast())
                    .isSameAs(failure);
            assertThat(logs.joined())
                    .contains("event=api_chat_stage_failure")
                    .contains("traceId=trace-unsampled-async-failure")
                    .contains("sampled=false");
        } finally {
            if (previousTrace == null) {
                MDC.remove("apiChatTraceId");
            } else {
                MDC.put("apiChatTraceId", previousTrace);
            }
        }
    }

    static class Target {
        private int subscriptions;
        private RuntimeException failure;

        @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.COMPLETION_SERVICE)
        public Flux<String> stream() {
            return Flux.defer(() -> {
                subscriptions++;
                return Flux.just("a", "b");
            });
        }

        @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.HTTP_CONTROLLER)
        public Mono<String> mono() {
            return Mono.defer(() -> {
                subscriptions++;
                return Mono.just("json");
            });
        }

        @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.COMPLETION_SERVICE)
        public Flux<String> fail() {
            throw failure;
        }

        @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.COMPLETION_SERVICE)
        public Flux<String> streamFailure() {
            return Flux.error(failure);
        }
    }

    private record LogCapture(
            Logger logger,
            ListAppender<ILoggingEvent> appender) implements AutoCloseable {

        private static LogCapture start() {
            Logger logger = (Logger) LoggerFactory.getLogger(
                    ApiChatStreamDiagnosticServiceImpl.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        private String joined() {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
