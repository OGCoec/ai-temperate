package com.example.temperate.web.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import java.io.EOFException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 验证模型图标业务错误映射为固定 HTTP 状态，且响应不会回显内部 URL 或 Object Key。
 */
final class AdminAiModelIconExceptionHandlerTest {

    private final AdminExceptionLogger exceptionLogger = mock(AdminExceptionLogger.class);
    private final AdminAiModelIconExceptionHandler handler =
            new AdminAiModelIconExceptionHandler(
                    Clock.fixed(
                            Instant.parse("2026-07-27T12:00:00Z"),
                            ZoneOffset.UTC),
                    exceptionLogger);

    @Test
    void mapsValidationNotFoundConflictAndStorageFailures() {
        assertStatus(AiModelIconErrorCode.AI_MODEL_ICON_INPUT_INVALID, HttpStatus.BAD_REQUEST);
        assertStatus(AiModelIconErrorCode.AI_MODEL_ICON_NOT_FOUND, HttpStatus.NOT_FOUND);
        assertStatus(AiModelIconErrorCode.AI_MODEL_ICON_NAME_CONFLICT, HttpStatus.CONFLICT);
        assertStatus(AiModelIconErrorCode.AI_MODEL_ICON_IN_USE, HttpStatus.CONFLICT);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE,
                HttpStatus.PAYLOAD_TOO_LARGE);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_STORAGE_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_DECODER_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED,
                HttpStatus.BAD_REQUEST);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                HttpStatus.BAD_REQUEST);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HOST_NOT_PUBLIC,
                HttpStatus.BAD_REQUEST);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_REDIRECT_INVALID,
                HttpStatus.BAD_REQUEST);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_RESPONSE_INVALID,
                HttpStatus.BAD_REQUEST);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_RESPONSE_TOO_LARGE,
                HttpStatus.PAYLOAD_TOO_LARGE);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_DNS_RESOLUTION_FAILED,
                HttpStatus.BAD_GATEWAY);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_FAILED,
                HttpStatus.BAD_GATEWAY);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED,
                HttpStatus.BAD_GATEWAY);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_HTTP_STATUS_INVALID,
                HttpStatus.BAD_GATEWAY);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_CONNECT_TIMEOUT,
                HttpStatus.GATEWAY_TIMEOUT);
        assertStatus(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_READ_TIMEOUT,
                HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void errorBodyKeepsStableMessageAndExposesAdministratorDiagnostics() {
        EOFException rootCause = new EOFException("SSL peer shut down incorrectly");
        SSLHandshakeException transportFailure =
                new SSLHandshakeException("Remote host terminated the handshake");
        transportFailure.initCause(rootCause);
        var response = handler.handle(new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_REMOTE_TLS_HANDSHAKE_FAILED,
                "Remote TLS handshake failed.",
                transportFailure));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo("外部图标 TLS 握手失败。");
        assertThat(response.getBody().exceptionType())
                .isEqualTo(SSLHandshakeException.class.getName());
        assertThat(response.getBody().exceptionMessage())
                .isEqualTo("Remote host terminated the handshake");
        assertThat(response.getBody().rootCauseType())
                .isEqualTo(EOFException.class.getName());
        assertThat(response.getBody().rootCauseMessage())
                .isEqualTo("SSL peer shut down incorrectly");
    }

    @Test
    void multipartTransportLimitAlsoReturnsPayloadTooLarge() {
        var response = handler.handleTooLarge(new MaxUploadSizeExceededException(2L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE.name());
    }

    private void assertStatus(AiModelIconErrorCode code, HttpStatus status) {
        var response = handler.handle(new AiModelIconException(code, "internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code.name());
    }
}
