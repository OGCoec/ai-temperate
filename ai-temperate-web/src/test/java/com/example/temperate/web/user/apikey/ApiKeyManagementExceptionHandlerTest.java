package com.example.temperate.web.user.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 该测试是来确保 API Key 专用异常边界先于全局兜底处理器执行，并将功能关闭映射为受控 503 而不是 500。
 */
final class ApiKeyManagementExceptionHandlerTest {

    @Test
    void featureDisabledUsesThePrioritizedApiKeyAdviceAndReturnsServiceUnavailable() {
        Order order = ApiKeyManagementExceptionHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);

        ApiKeyManagementExceptionHandler handler = new ApiKeyManagementExceptionHandler(
                Clock.fixed(Instant.parse("2026-08-14T11:50:00Z"), ZoneOffset.UTC));
        var response = handler.handle(new ApiKeyManagementException(
                ApiKeyManagementErrorCode.FEATURE_DISABLED,
                "API Key management is disabled"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("FEATURE_DISABLED");
        assertThat(response.getHeaders().getCacheControl())
                .contains("private")
                .contains("no-store");
    }

    @Test
    void createLockContentionReturnsConflictWithRetryAfter() {
        ApiKeyManagementExceptionHandler handler = new ApiKeyManagementExceptionHandler(
                Clock.fixed(Instant.parse("2026-08-17T11:50:00Z"), ZoneOffset.UTC));

        var response = handler.handle(new ApiKeyManagementException(
                ApiKeyManagementErrorCode.API_KEY_CREATE_IN_PROGRESS,
                "API Key creation is already in progress"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("API_KEY_CREATE_IN_PROGRESS");
    }

    @Test
    void completedCreateReturnsConflictWithoutPretendingThePlaintextCanBeReplayed() {
        ApiKeyManagementExceptionHandler handler = new ApiKeyManagementExceptionHandler(
                Clock.fixed(Instant.parse("2026-08-17T11:50:00Z"), ZoneOffset.UTC));

        var response = handler.handle(new ApiKeyManagementException(
                ApiKeyManagementErrorCode.API_KEY_CREATE_ALREADY_COMPLETED,
                "already completed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .contains("完整 API Key 无法再次获取")
                .doesNotContain("sk-");
    }
}
