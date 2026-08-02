package com.example.temperate.web.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 验证管理员 AI 模型异常响应保持原有协议，并把日志交给共享的管理员异常策略。
 */
final class AdminAiModelExceptionHandlerTest {

    @Test
    void notFoundKeepsResponseContractAndUsesSharedLogger() {
        AdminExceptionLogger exceptionLogger = mock(AdminExceptionLogger.class);
        AdminAiModelExceptionHandler handler = new AdminAiModelExceptionHandler(
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
                exceptionLogger);
        AdminAiModelException exception = new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_NOT_FOUND,
                "internal detail");

        var response = handler.handle(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_NOT_FOUND.name());
        assertThat(response.getBody().message()).isEqualTo("AI 模型不存在。");
        verify(exceptionLogger).logKnown(
                "admin_ai_model_rejected",
                AdminAiModelErrorCode.AI_MODEL_NOT_FOUND.name(),
                HttpStatus.NOT_FOUND,
                exception);
    }

    @Test
    void missingTokenLimitMapsToConflict() {
        AdminExceptionLogger exceptionLogger = mock(AdminExceptionLogger.class);
        AdminAiModelExceptionHandler handler = new AdminAiModelExceptionHandler(
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
                exceptionLogger);
        AdminAiModelException exception = new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_REQUIRED,
                "internal detail");

        var response = handler.handle(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_REQUIRED.name());
        assertThat(response.getBody().message())
                .isEqualTo("必须先配置模型上下文与最大输出 Token 上限。");
    }

    @Test
    void invalidTokenLimitMapsToBadRequest() {
        AdminAiModelExceptionHandler handler = new AdminAiModelExceptionHandler(
                Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
                mock(AdminExceptionLogger.class));

        var response = handler.handle(new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID,
                "internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID.name());
    }
}
