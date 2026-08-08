package com.example.temperate.service.user.aiconversation.diagnostic.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiUpstreamErrorDiagnostic;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.exception.AiUpstreamHttpStatusException;
import com.fasterxml.jackson.core.JsonParseException;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

/**
 * 验证流式异常只依据异常类型、受控业务码和 HTTP 状态生成可公开的停止原因。
 */
final class AiConversationStreamFailureClassifierImplTest {

    private final AiConversationStreamFailureClassifierImpl classifier =
            new AiConversationStreamFailureClassifierImpl();

    @Test
    void classifiesKnownFailuresWithoutDependingOnExceptionMessage() {
        assertReason(new TimeoutException("secret-timeout-body"),
                AiConversationStreamFailureReason.UPSTREAM_TOTAL_TIMEOUT);
        assertReason(new ClosedChannelException(),
                AiConversationStreamFailureReason.UPSTREAM_CONNECTION_CLOSED);
        assertReason(new IOException("https://proxy.invalid?api_key=secret"),
                AiConversationStreamFailureReason.UPSTREAM_NETWORK_ERROR);
        assertReason(new JsonParseException(null, "secret-provider-body"),
                AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR);
        assertReason(new StatusFailure(429, "secret-rate-limit-body"),
                AiConversationStreamFailureReason.UPSTREAM_RATE_LIMITED);
        assertReason(new StatusFailure(403, "secret-auth-body"),
                AiConversationStreamFailureReason.UPSTREAM_AUTH_UNAVAILABLE);
        assertReason(new StatusFailure(503, "secret-server-body"),
                AiConversationStreamFailureReason.UPSTREAM_SERVER_ERROR);
        assertReason(new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "safe-upstream-status-only"),
                AiConversationStreamFailureReason.UPSTREAM_RATE_LIMITED);
    }

    @Test
    void preservesExplicitReasonAndFindsBoundedRootCause() {
        ClosedChannelException root = new ClosedChannelException();
        AiConversationException failure = new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型响应未能完成",
                true,
                AiConversationStreamFailureReason.UPSTREAM_CONNECTION_CLOSED,
                root);

        AiConversationStreamFailureClassification result =
                classifier.classify(failure);

        assertThat(result.reason()).isEqualTo(
                AiConversationStreamFailureReason.UPSTREAM_CONNECTION_CLOSED);
        assertThat(result.exceptionType()).isEqualTo(
                AiConversationException.class.getName());
        assertThat(result.rootCauseType()).isEqualTo(
                ClosedChannelException.class.getName());
        assertThat(result.stackFingerprint()).isNotBlank();
    }

    @Test
    void mapsMissingFinalUsageAndUnknownFailuresToStableReasons() {
        assertReason(new AiConversationException(
                        AiConversationErrorCode.AI_USAGE_UNAVAILABLE,
                        "模型没有返回可靠的最终用量",
                        false),
                AiConversationStreamFailureReason.USAGE_DATA_UNAVAILABLE);
        assertReason(new IllegalStateException("untrusted-secret-message"),
                AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE);
    }

    @Test
    void classifiesReactorOverflowAsLocalStreamBackpressureFailure() {
        assertReason(
                Exceptions.failWithOverflow(),
                AiConversationStreamFailureReason.STREAM_BACKPRESSURE_OVERFLOW);
    }

    @Test
    void preservesSanitizedDiagnosticThroughAControlledExceptionWrapper() {
        AiUpstreamErrorDiagnostic diagnostic = new AiUpstreamErrorDiagnostic(
                "invalid_request_error",
                "extra_forbidden",
                "body.tools.0.search_context_size",
                "Extra inputs are not permitted",
                "req-safe",
                "application/json",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                128,
                false);
        AiUpstreamHttpStatusException upstream =
                new AiUpstreamHttpStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        diagnostic);
        AiConversationException wrapped = new AiConversationException(
                AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                "模型联网响应未能完成",
                true,
                upstream);

        AiConversationStreamFailureClassification result =
                classifier.classify(wrapped);

        assertThat(result.reason()).isEqualTo(
                AiConversationStreamFailureReason
                        .UPSTREAM_TOOL_CONFIGURATION_UNSUPPORTED);
        assertThat(result.upstreamStatus()).isEqualTo(422);
        assertThat(result.upstreamDiagnostic()).isEqualTo(diagnostic);
        assertThat(result.rootCauseType()).isEqualTo(
                AiUpstreamHttpStatusException.class.getName());
        assertThat(result.stackFingerprint()).isNotBlank();
    }

    @Test
    void classifiesOnlyExplicitCompatibilityParametersFor400And422() {
        assertCompatibility("generation_config.thinking_level",
                AiConversationStreamFailureReason
                        .UPSTREAM_REASONING_LEVEL_UNSUPPORTED);
        assertCompatibility("response_format.image_size",
                AiConversationStreamFailureReason
                        .UPSTREAM_IMAGE_RESOLUTION_UNSUPPORTED);
        assertCompatibility("body.tool_choice",
                AiConversationStreamFailureReason
                        .UPSTREAM_TOOL_CONFIGURATION_UNSUPPORTED);

        AiUpstreamHttpStatusException unknown = new AiUpstreamHttpStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                diagnostic("body.input.0.content.0.text"));
        assertReason(unknown,
                AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE);
    }

    private void assertCompatibility(
            String parameter,
            AiConversationStreamFailureReason expected) {
        assertReason(new AiUpstreamHttpStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                diagnostic(parameter)), expected);
    }

    private static AiUpstreamErrorDiagnostic diagnostic(String parameter) {
        return new AiUpstreamErrorDiagnostic(
                "invalid_request_error",
                "unsupported_value",
                parameter,
                "Unsupported parameter value",
                "req-safe",
                "application/json",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                128,
                false);
    }

    private void assertReason(
            Throwable failure, AiConversationStreamFailureReason expected) {
        assertThat(classifier.classify(failure).reason()).isEqualTo(expected);
    }

    /**
     * 模拟仅公开状态码访问器的上游客户端异常，避免测试依赖具体 HTTP 客户端实现。
     */
    public static final class StatusFailure extends RuntimeException {
        private final TestStatus statusCode;

        private StatusFailure(int statusCode, String message) {
            super(message);
            this.statusCode = new TestStatus(statusCode);
        }

        public TestStatus getStatusCode() {
            return statusCode;
        }
    }

    /**
     * 提供与 Spring HTTP 状态对象相同的最小反射契约。
     */
    public record TestStatus(int value) {
    }
}
