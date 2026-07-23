package com.example.temperate.web.auth.interceptor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.web.auth.config.properties.AuthSecurityProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 H5 会话请求的 Origin、Fetch Metadata 校验及 Android 绕过边界的测试。
 */
class BrowserSessionSecurityInterceptorTest {

    private BrowserSessionSecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        AuthSecurityProperties properties = mock(AuthSecurityProperties.class);
        when(properties.cors()).thenReturn(new AuthSecurityProperties.Cors(
                List.of("https://app.example.test")));
        interceptor = new BrowserSessionSecurityInterceptor(properties);
    }

    @Test
    void bootstrapNeedsAnAllowedOriginButNotAnExistingCsrfHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/session/bootstrap");
        request.addHeader("Origin", "https://app.example.test");
        request.addHeader("Sec-Fetch-Site", "same-site");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void rejectsCrossSiteBrowserRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/session/bootstrap");
        request.addHeader("Origin", "https://attacker.example");
        request.addHeader("Sec-Fetch-Site", "cross-site");

        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(SessionAuthenticationErrorCode.CSRF_INVALID));
    }

    @Test
    void rejectsBrowserRequestsWithoutFetchMetadata() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/session/bootstrap");
        request.addHeader("Origin", "https://app.example.test");

        assertThatThrownBy(() -> interceptor.preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(SessionAuthenticationException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(SessionAuthenticationErrorCode.CSRF_INVALID));
    }
}
