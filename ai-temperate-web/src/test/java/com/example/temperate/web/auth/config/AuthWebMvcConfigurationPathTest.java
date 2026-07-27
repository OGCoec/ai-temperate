package com.example.temperate.web.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.web.auth.interceptor.AccessTokenAuthenticationInterceptor;
import com.example.temperate.web.auth.interceptor.BrowserSessionSecurityInterceptor;
import com.example.temperate.web.auth.interceptor.RegistrationFlowInterceptor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * 验证普通用户 MVC 认证拦截器与管理员独立认证命名空间之间的路由隔离边界。
 */
class AuthWebMvcConfigurationPathTest {

    private AccessTokenAuthenticationInterceptor accessTokenInterceptor;
    private List<Object> registeredInterceptors;

    @BeforeEach
    void setUp() {
        accessTokenInterceptor = mock(AccessTokenAuthenticationInterceptor.class);
        AuthWebMvcConfiguration configuration = new AuthWebMvcConfiguration(
                accessTokenInterceptor,
                mock(RegistrationFlowInterceptor.class),
                mock(BrowserSessionSecurityInterceptor.class));
        InterceptorRegistry registry = new InterceptorRegistry();
        configuration.addInterceptors(registry);
        registeredInterceptors = ReflectionTestUtils.invokeMethod(
                registry, "getInterceptors");
    }

    @Test
    void administratorNamespaceDoesNotUseOrdinaryAccessTokenAuthentication() {
        assertThat(matchesAccessTokenInterceptor("/api/admin/auth/state"))
                .isFalse();
    }

    @Test
    void ordinaryProtectedApiStillUsesAccessTokenAuthentication() {
        assertThat(matchesAccessTokenInterceptor("/api/users/me"))
                .isTrue();
    }

    @Test
    void ordinaryPreAuthBootstrapDoesNotUseAccessTokenAuthentication() {
        assertThat(matchesAccessTokenInterceptor("/api/_edge/pre-auth"))
                .isFalse();
    }

    @Test
    void ordinaryRiskChallengeNavigationDoesNotUseAccessTokenAuthentication() {
        assertThat(matchesAccessTokenInterceptor("/api/_edge/risk-challenge"))
                .isFalse();
    }

    @Test
    void ordinaryWebRtcStartAndReportDoNotUseAccessTokenAuthentication() {
        assertThat(matchesAccessTokenInterceptor("/api/_edge/webrtc/start"))
                .isFalse();
        assertThat(matchesAccessTokenInterceptor("/api/_edge/webrtc/report"))
                .isFalse();
    }

    private boolean matchesAccessTokenInterceptor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return registeredInterceptors.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> sameInterceptor(
                        mapped.getInterceptor(), accessTokenInterceptor))
                .anyMatch(mapped -> mapped.matches(request));
    }

    private static boolean sameInterceptor(
            HandlerInterceptor actual,
            HandlerInterceptor expected) {
        return actual == expected;
    }
}
