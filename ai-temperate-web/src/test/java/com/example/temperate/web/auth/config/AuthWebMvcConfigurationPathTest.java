package com.example.temperate.web.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.web.auth.interceptor.BrowserSessionSecurityInterceptor;
import com.example.temperate.web.auth.interceptor.RegistrationFlowInterceptor;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

/**
 * 验证普通用户 MVC 认证拦截器与管理员独立认证命名空间之间的路由隔离边界。
 */
class AuthWebMvcConfigurationPathTest {

    private UserSessionAuthenticationInterceptor userSessionInterceptor;
    private RegistrationFlowInterceptor registrationFlowInterceptor;
    private BrowserSessionSecurityInterceptor browserSessionSecurityInterceptor;
    private List<InterceptorRegistration> registrations;
    private List<Object> registeredInterceptors;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userSessionInterceptor = mock(UserSessionAuthenticationInterceptor.class);
        registrationFlowInterceptor = mock(RegistrationFlowInterceptor.class);
        browserSessionSecurityInterceptor = mock(BrowserSessionSecurityInterceptor.class);
        AuthWebMvcConfiguration configuration = new AuthWebMvcConfiguration(
                userSessionInterceptor,
                registrationFlowInterceptor,
                browserSessionSecurityInterceptor);
        InterceptorRegistry registry = new InterceptorRegistry();
        configuration.addInterceptors(registry);
        registrations = (List<InterceptorRegistration>) ReflectionTestUtils.getField(
                registry, "registrations");
        registeredInterceptors = ReflectionTestUtils.invokeMethod(
                registry, "getInterceptors");
    }

    @Test
    void ordinaryAuthenticationInterceptorsHaveExplicitStableOrders() {
        assertThat(ordersOf(registrationFlowInterceptor))
                .containsExactly(Ordered.HIGHEST_PRECEDENCE + 20);
        assertThat(ordersOf(browserSessionSecurityInterceptor))
                .containsExactly(Ordered.HIGHEST_PRECEDENCE + 21);
        assertThat(ordersOf(userSessionInterceptor))
                .containsExactly(
                        Ordered.HIGHEST_PRECEDENCE + 22,
                        Ordered.HIGHEST_PRECEDENCE + 22);
    }

    @Test
    void overlappingLogoutAllRunsBrowserSecurityBeforeUserSessionAuthentication() {
        assertThat(matchingInterceptors("/api/auth/session/logout-all"))
                .containsExactly(
                        browserSessionSecurityInterceptor,
                        userSessionInterceptor);
    }

    @Test
    void registrationLogoutAndOrdinaryApiKeepTheirDedicatedInterceptors() {
        assertThat(matchingInterceptors("/api/auth/register/status"))
                .containsExactly(registrationFlowInterceptor);
        assertThat(matchingInterceptors("/api/auth/session/logout"))
                .containsExactly(browserSessionSecurityInterceptor);
        assertThat(matchingInterceptors("/api/users/me"))
                .containsExactly(userSessionInterceptor);
    }

    @Test
    void administratorNamespaceDoesNotUseOrdinaryUserSessionAuthentication() {
        assertThat(matchesUserSessionInterceptor("/api/admin/auth/state"))
                .isFalse();
    }

    @Test
    void ordinaryProtectedApiUsesRtFirstUserSessionAuthentication() {
        assertThat(matchesUserSessionInterceptor("/api/users/me"))
                .isTrue();
        assertThat(matchesUserSessionInterceptor("/api/ai-models"))
                .isTrue();
        assertThat(matchesUserSessionInterceptor("/api/ai-models/AAABi0VWeJ8"))
                .isTrue();
        assertThat(matchesUserSessionInterceptor("/api/ai/conversations"))
                .isTrue();
        assertThat(matchesUserSessionInterceptor("/api/ai/conversations/responses"))
                .isTrue();
        assertThat(matchesUserSessionInterceptor(
                        "/api/ai/conversations/AZ-vpV3kfag70-0EMMUETQ/messages"))
                .isTrue();
        assertThat(matchesUserSessionInterceptor(
                        "/api/ai/conversation-attachments/preuploads"))
                .isTrue();
    }

    @Test
    void ordinaryPreAuthBootstrapDoesNotUseUserSessionAuthentication() {
        assertThat(matchesUserSessionInterceptor("/api/_edge/pre-auth"))
                .isFalse();
    }

    @Test
    void ordinaryRiskChallengeNavigationDoesNotUseUserSessionAuthentication() {
        assertThat(matchesUserSessionInterceptor("/api/_edge/risk-challenge"))
                .isFalse();
    }

    @Test
    void ordinaryWebRtcStartAndReportDoNotUseUserSessionAuthentication() {
        assertThat(matchesUserSessionInterceptor("/api/_edge/webrtc/start"))
                .isFalse();
        assertThat(matchesUserSessionInterceptor("/api/_edge/webrtc/report"))
                .isFalse();
    }

    private boolean matchesUserSessionInterceptor(String path) {
        return matchingInterceptors(path).stream()
                .anyMatch(interceptor -> sameInterceptor(
                        interceptor, userSessionInterceptor));
    }

    private List<HandlerInterceptor> matchingInterceptors(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        ServletRequestPathUtils.parseAndCache(request);
        return registeredInterceptors.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.matches(request))
                .map(MappedInterceptor::getInterceptor)
                .toList();
    }

    private List<Integer> ordersOf(HandlerInterceptor expected) {
        return registrations.stream()
                .filter(candidate -> sameInterceptor(
                        interceptorOf(candidate), expected))
                .map(AuthWebMvcConfigurationPathTest::registeredOrder)
                .toList();
    }

    private static HandlerInterceptor interceptorOf(
            InterceptorRegistration registration) {
        Object registered = ReflectionTestUtils.invokeMethod(
                registration, "getInterceptor");
        if (registered instanceof MappedInterceptor mappedInterceptor) {
            return mappedInterceptor.getInterceptor();
        }
        return (HandlerInterceptor) registered;
    }

    private static int registeredOrder(InterceptorRegistration registration) {
        Integer order = ReflectionTestUtils.invokeMethod(registration, "getOrder");
        return order == null ? Integer.MAX_VALUE : order;
    }

    private static boolean sameInterceptor(
            HandlerInterceptor actual,
            HandlerInterceptor expected) {
        return actual == expected;
    }
}
