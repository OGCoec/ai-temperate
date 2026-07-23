package com.example.temperate.web.auth.interceptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.web.auth.session.transport.AuthCookieWriter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 验证 H5 Cookie 与 Android Bearer AT 来源严格隔离的拦截器测试。
 */
class AccessTokenAuthenticationInterceptorTest {

    private AccessSessionService service;
    private AccessTokenAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        service = mock(AccessSessionService.class);
        interceptor = new AccessTokenAuthenticationInterceptor(service);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void h5ReadsOnlyTheAccessCookieAndIgnoresAuthorization() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        request.addHeader("Authorization", "Bearer android-at");
        request.setCookies(new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"));
        SessionPrincipal principal = new SessionPrincipal(1L, "AAAAAAAAAAE", "User");
        when(service.authenticate("browser-at")).thenReturn(principal);

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(service).authenticate("browser-at");
    }

    @Test
    void androidReadsOnlyAuthorizationAndIgnoresTheAccessCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("Authorization", "Bearer android-at");
        request.setCookies(new Cookie(AuthCookieWriter.ACCESS_COOKIE, "browser-at"));
        SessionPrincipal principal = new SessionPrincipal(1L, "AAAAAAAAAAE", "User");
        when(service.authenticate("android-at")).thenReturn(principal);

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(service).authenticate("android-at");
    }

    @Test
    void h5DoesNotFallBackToAuthorizationWhenItsCookieIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "H5");
        request.addHeader("Authorization", "Bearer ignored-android-at");
        when(service.authenticate(null))
                .thenReturn(new SessionPrincipal(1L, "AAAAAAAAAAE", "User"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(service).authenticate(null);
    }

    @Test
    void androidDoesNotFallBackToCookiesWhenAuthorizationIsMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");
        request.setCookies(new Cookie(AuthCookieWriter.ACCESS_COOKIE, "ignored-browser-at"));
        when(service.authenticate(null))
                .thenReturn(new SessionPrincipal(1L, "AAAAAAAAAAE", "User"));

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(service).authenticate(null);
    }
}
