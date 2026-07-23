package com.example.temperate.web.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.MissingCsrfTokenException;

/**
 * 验证 Spring CSRF 拒绝会返回稳定 403 JSON 错误码且不泄露 Token 的测试。
 */
class JsonCsrfAccessDeniedHandlerTest {

    @Test
    void returnsTheStableCsrfErrorWithoutLeakingTokenData() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JsonCsrfAccessDeniedHandler handler = new JsonCsrfAccessDeniedHandler(
                objectMapper,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(
                new MockHttpServletRequest(),
                response,
                new MissingCsrfTokenException("missing"));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("code").asText()).isEqualTo("CSRF_INVALID");
        assertThat(body.path("message").asText()).isEqualTo("CSRF token is invalid.");
        assertThat(response.getContentAsString()).doesNotContain("missing");
    }
}
