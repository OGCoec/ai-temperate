package com.example.temperate.web.user.aimodel.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogErrorCode;
import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogException;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 验证普通用户模型目录错误不会泄露内部 ID，并映射为稳定 HTTP 状态。
 */
final class UserAiModelCatalogExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private final UserAiModelCatalogExceptionHandler handler =
            new UserAiModelCatalogExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void mapsUnavailableModelToNotFound() {
        ResponseEntity<ApiErrorResponse> response = handler.handle(
                new UserAiModelCatalogException(
                        UserAiModelCatalogErrorCode.AI_MODEL_NOT_FOUND,
                        "AI model does not exist or is not enabled."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("AI_MODEL_NOT_FOUND");
        assertThat(response.getBody().timestamp()).isEqualTo(NOW);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void mapsInvalidPublicIdToBadRequest() {
        ResponseEntity<ApiErrorResponse> response = handler.handle(
                new UserAiModelCatalogException(
                        UserAiModelCatalogErrorCode.AI_MODEL_PUBLIC_ID_INVALID,
                        "AI model public ID is invalid."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
