package com.example.temperate.web.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.risk.ip2location.exception.Ip2LocationApiKeyCapacityExceededException;
import com.example.temperate.web.admin.security.AdminClientPlatformResolver;
import com.example.temperate.web.admin.transport.AdminCookieWriter;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 验证管理员凭据池容量竞争只返回稳定冲突码，不泄露 Redis 字段或输入 Key。
 */
class AdminIp2LocationCapacityExceptionHandlerTest {

    @Test
    void capacityErrorUsesConflictAndSanitizedResponse() {
        AdminWebExceptionHandler handler = new AdminWebExceptionHandler(
                Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC),
                mock(AdminCookieWriter.class),
                mock(AdminClientPlatformResolver.class),
                mock(AdminExceptionLogger.class));

        ResponseEntity<ApiErrorResponse> response = handler.handleIp2LocationCapacity(
                new Ip2LocationApiKeyCapacityExceededException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("IP2LOCATION_KEY_LIMIT_EXCEEDED");
        assertThat(response.getBody().message()).doesNotContain("Redis", "Hash", "Key ID");
    }
}
