package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.diagnostic.AiUpstreamErrorDiagnostic;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;

/**
 * 验证上游错误正文只会在固定内存边界内被解析，并在进入异常链前完成字段白名单和敏感信息脱敏。
 */
final class AiUpstreamErrorCaptureTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiUpstreamErrorCapture capture =
            new AiUpstreamErrorCapture(objectMapper);

    @Test
    void extractsOpenAiErrorEnvelopeAndSafeRequestMetadata() {
        ClientResponse response = response(
                "application/json",
                "req-safe_123",
                """
                {
                  "error": {
                    "code": "invalid_request_error",
                    "type": "validation_error",
                    "param": "reasoning.summary",
                    "message": "Unsupported value: auto"
                  }
                }
                """);

        AiUpstreamErrorDiagnostic diagnostic = capture.capture(response).block();

        assertThat(diagnostic).isNotNull();
        assertThat(diagnostic.providerCode()).isEqualTo("invalid_request_error");
        assertThat(diagnostic.providerType()).isEqualTo("validation_error");
        assertThat(diagnostic.providerParam()).isEqualTo("reasoning.summary");
        assertThat(diagnostic.sanitizedMessage())
                .isEqualTo("Unsupported value: auto");
        assertThat(diagnostic.requestId()).isEqualTo("req-safe_123");
        assertThat(diagnostic.contentType()).isEqualTo("application/json");
        assertThat(diagnostic.bodySha256()).matches("[A-Za-z0-9_-]{43}");
        assertThat(diagnostic.capturedBytes()).isPositive();
        assertThat(diagnostic.truncated()).isFalse();
        assertThat(diagnostic.toString())
                .isEqualTo("AiUpstreamErrorDiagnostic[redacted]");
    }

    @Test
    void extractsFastApiDetailWithoutCapturingRejectedInput() {
        String rejectedInput = "PRIVATE_PROMPT_MUST_NOT_ESCAPE";
        ClientResponse response = response(
                "application/json",
                null,
                """
                {
                  "detail": [{
                    "type": "extra_forbidden",
                    "loc": ["body", "tools", 0, "search_context_size"],
                    "msg": "Extra inputs are not permitted",
                    "input": "%s"
                  }]
                }
                """.formatted(rejectedInput));

        AiUpstreamErrorDiagnostic diagnostic = capture.capture(response).block();

        assertThat(diagnostic).isNotNull();
        assertThat(diagnostic.providerType()).isEqualTo("extra_forbidden");
        assertThat(diagnostic.providerParam())
                .isEqualTo("body.tools.0.search_context_size");
        assertThat(diagnostic.sanitizedMessage())
                .isEqualTo("Extra inputs are not permitted")
                .doesNotContain(rejectedInput);
    }

    @Test
    void unwrapsSerializedProviderJsonWithoutLoggingItsInputField() {
        String rejectedInput = "SERIALIZED_PRIVATE_INPUT";
        String nested = """
                {
                  "detail": [{
                    "type": "extra_forbidden",
                    "loc": ["body", "reasoning", "summary"],
                    "msg": "Unsupported value: auto",
                    "input": "%s"
                  }]
                }
                """.formatted(rejectedInput);
        var wrapper = objectMapper.createObjectNode();
        wrapper.putObject("error")
                .put("type", "api_error")
                .put("message",
                        "request error, error status: 422, error message: "
                                + nested);

        AiUpstreamErrorDiagnostic diagnostic = capture.capture(response(
                "application/json",
                null,
                wrapper.toString())).block();

        assertThat(diagnostic).isNotNull();
        assertThat(diagnostic.providerType()).isEqualTo("extra_forbidden");
        assertThat(diagnostic.providerParam())
                .isEqualTo("body.reasoning.summary");
        assertThat(diagnostic.sanitizedMessage())
                .isEqualTo("Unsupported value: auto")
                .doesNotContain(rejectedInput);
    }

    @Test
    void redactsSecretsPersonalDataUrlsAndLogControlCharacters() {
        String rawToken = "abcdefghijklmnopqrstuvwxyz0123456789";
        String message = "Bearer " + rawToken
                + " api_key=top-secret-value"
                + " email=user@example.com phone=+1 312-555-0199"
                + " ip=203.0.113.7"
                + " url=https://example.com/path?token=secret"
                + " opaque=" + rawToken
                + "\r\nforged=true";
        ClientResponse response = response(
                "application/json",
                null,
                "{\"message\":\"" + jsonEscape(message) + "\"}");

        AiUpstreamErrorDiagnostic diagnostic = capture.capture(response).block();

        assertThat(diagnostic).isNotNull();
        assertThat(diagnostic.sanitizedMessage())
                .contains("<redacted-credential>")
                .contains("<redacted-email>")
                .contains("<redacted-phone>")
                .contains("<redacted-ip>")
                .contains("<redacted-url>")
                .doesNotContain(rawToken)
                .doesNotContain("top-secret-value")
                .doesNotContain("user@example.com")
                .doesNotContain("312-555-0199")
                .doesNotContain("203.0.113.7")
                .doesNotContain("\r")
                .doesNotContain("\n");
    }

    @Test
    void boundsMessagesAndFallsBackForMalformedOrOversizedBodies() {
        String longMessage = "m".repeat(700);
        AiUpstreamErrorDiagnostic bounded = capture.capture(response(
                "application/json",
                null,
                "{\"message\":\"" + longMessage + "\"}")).block();
        AiUpstreamErrorDiagnostic malformed = capture.capture(response(
                "text/plain",
                null,
                "not-json-provider-body")).block();
        String oversizedBody = "x".repeat(16 * 1024 + 128);
        AiUpstreamErrorDiagnostic oversized = capture.capture(response(
                "text/plain",
                null,
                oversizedBody)).block();

        assertThat(bounded).isNotNull();
        assertThat(bounded.sanitizedMessage()).hasSize(512);
        assertThat(malformed).isNotNull();
        assertThat(malformed.providerCode()).isEqualTo("unavailable");
        assertThat(malformed.sanitizedMessage()).isEqualTo("unavailable");
        assertThat(malformed.bodySha256()).matches("[A-Za-z0-9_-]{43}");
        assertThat(oversized).isNotNull();
        assertThat(oversized.capturedBytes()).isEqualTo(16 * 1024);
        assertThat(oversized.truncated()).isTrue();
        assertThat(oversized.sanitizedMessage()).isEqualTo("unavailable");
    }

    @Test
    void representsAnEmptyBodyWithoutInventingProviderFields() {
        AiUpstreamErrorDiagnostic diagnostic = capture.capture(response(
                "application/json",
                null,
                "")).block();

        assertThat(diagnostic).isNotNull();
        assertThat(diagnostic.providerCode()).isEqualTo("unavailable");
        assertThat(diagnostic.sanitizedMessage()).isEqualTo("unavailable");
        assertThat(diagnostic.capturedBytes()).isZero();
        assertThat(diagnostic.truncated()).isFalse();
        assertThat(diagnostic.bodySha256()).matches("[A-Za-z0-9_-]{43}");
    }

    private static ClientResponse response(
            String contentType,
            String requestId,
            String body) {
        ClientResponse.Builder builder = ClientResponse.create(
                        HttpStatus.UNPROCESSABLE_ENTITY)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(body);
        if (requestId != null) {
            builder.header("x-request-id", requestId);
        }
        return builder.build();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
