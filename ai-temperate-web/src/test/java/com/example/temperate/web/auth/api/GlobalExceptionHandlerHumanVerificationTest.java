package com.example.temperate.web.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.humanverification.HumanVerificationType;
import com.example.temperate.service.humanverification.exception.HumanVerificationUnavailableException;
import com.example.temperate.web.auth.flow.transport.AuthFlowCookieWriter;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 验证人机供应商不可用异常会通过同步和异步 MVC 路径稳定映射为脱敏的 503 响应。
 */
class GlobalExceptionHandlerHumanVerificationTest {

    private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");

    private AuthCookieWriter cookieWriter;
    private AuthFlowCookieWriter flowCookieWriter;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        cookieWriter = mock(AuthCookieWriter.class);
        flowCookieWriter = mock(AuthFlowCookieWriter.class);
        handler = new GlobalExceptionHandler(
                Clock.fixed(NOW, ZoneOffset.UTC),
                cookieWriter,
                flowCookieWriter,
                mock(com.example.temperate.web.risk.PreAuthTransport.class));
    }

    @Test
    void returnsStableNonCachedResponseWithoutClearingFlowCookies() {
        HumanVerificationUnavailableException exception =
                unavailable("sensitive-provider-detail");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");

        var response = handler.handleHumanVerificationUnavailable(
                exception,
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(response.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code())
                .isEqualTo("HUMAN_VERIFICATION_UNAVAILABLE");
        assertThat(response.getBody().message())
                .isEqualTo("人机验证服务暂时不可用，请稍后重试。");
        assertThat(response.getBody().timestamp()).isEqualTo(NOW);
        assertThat(response.getBody().toString())
                .doesNotContain("sensitive-provider-detail")
                .doesNotContain("IllegalStateException");
        verifyNoInteractions(cookieWriter, flowCookieWriter);
    }

    @Test
    void catchesUnavailableErrorFromAsyncMonoInsteadOfReturningGenericServerError()
            throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new UnavailableProbeController())
                .setControllerAdvice(handler)
                .build();

        MvcResult pending = mockMvc.perform(
                        get("/test/human-verification-unavailable"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("HUMAN_VERIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("人机验证服务暂时不可用，请稍后重试。"))
                .andExpect(content().string(
                        not(containsString("sensitive-provider-detail"))));
    }

    private static HumanVerificationUnavailableException unavailable(
            String causeMessage) {
        return new HumanVerificationUnavailableException(
                HumanVerificationType.TURNSTILE,
                new IllegalStateException(causeMessage));
    }

    /**
     * 仅为验证 Spring MVC 对冷 Mono 异步错误的 Advice 分派行为提供最小端点。
     */
    @RestController
    static final class UnavailableProbeController {

        @GetMapping("/test/human-verification-unavailable")
        public Mono<Void> unavailable() {
            return Mono.error(GlobalExceptionHandlerHumanVerificationTest.unavailable(
                    "sensitive-provider-detail"));
        }
    }
}
