package com.example.temperate.web.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.temperate.web.auth.config.AuthWebMvcConfiguration;
import com.example.temperate.web.auth.interceptor.BrowserSessionSecurityInterceptor;
import com.example.temperate.web.auth.interceptor.RegistrationFlowInterceptor;
import com.example.temperate.web.auth.interceptor.UserSessionAuthenticationInterceptor;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import com.example.temperate.web.risk.NetworkRiskWebMvcConfiguration;
import com.example.temperate.web.risk.webrtc.WebRtcVerificationInterceptor;
import com.example.temperate.web.risk.webrtc.WebRtcWebMvcConfiguration;
import com.example.temperate.web.user.membership.payment.callback.BarPaymentCallbackController;
import com.example.temperate.web.user.membership.payment.callback.SimulatedLiuhaoPaymentCallbackController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * 该路径测试是来确认当前用户会员购买接口进入网络风险、WebRTC 和 RT-first 会话拦截器，而支付回调只走各自签名边界。
 */
final class MembershipPaymentInterceptorPathTest {

    private UserSessionAuthenticationInterceptor userSessionInterceptor;
    private NetworkRiskInterceptor networkRiskInterceptor;
    private WebRtcVerificationInterceptor webRtcInterceptor;
    private List<Object> authInterceptors;
    private List<Object> networkInterceptors;
    private List<Object> webRtcInterceptors;

    @BeforeEach
    void setUp() {
        userSessionInterceptor = mock(UserSessionAuthenticationInterceptor.class);
        networkRiskInterceptor = mock(NetworkRiskInterceptor.class);
        webRtcInterceptor = mock(WebRtcVerificationInterceptor.class);
        authInterceptors = registered(new AuthWebMvcConfiguration(
                userSessionInterceptor,
                mock(RegistrationFlowInterceptor.class),
                mock(BrowserSessionSecurityInterceptor.class)));
        networkInterceptors = registered(
                new NetworkRiskWebMvcConfiguration(networkRiskInterceptor));
        webRtcInterceptors = registered(
                new WebRtcWebMvcConfiguration(webRtcInterceptor));
    }

    @Test
    void allCurrentUserMembershipOrderRoutesUseTheThreeBusinessInterceptors() {
        for (String path : List.of(
                "/api/user/membership-orders",
                "/api/user/membership-plan-offers",
                "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA",
                "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA/cancel",
                "/api/user/membership-orders/AaAjECcaAQGqi_h2Rl1PiA/payment-attempts")) {
            assertThat(matching(authInterceptors, path))
                    .as("RT-first session interceptor for %s", path)
                    .contains(userSessionInterceptor);
            assertThat(matching(networkInterceptors, path))
                    .as("network-risk interceptor for %s", path)
                    .contains(networkRiskInterceptor);
            assertThat(matching(webRtcInterceptors, path))
                    .as("WebRTC interceptor for %s", path)
                    .contains(webRtcInterceptor);
        }
    }

    @Test
    void internalCallbackDoesNotAccidentallyEnterCurrentUserInterceptors() {
        String callbackPath = SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH;

        assertThat(matching(authInterceptors, callbackPath))
                .doesNotContain(userSessionInterceptor);
        assertThat(matching(networkInterceptors, callbackPath))
                .doesNotContain(networkRiskInterceptor);
        assertThat(matching(webRtcInterceptors, callbackPath))
                .doesNotContain(webRtcInterceptor);
    }

    @Test
    void barCallbackDoesNotRequireBrowserSessionOrBrowserRiskContext() {
        String callbackPath = BarPaymentCallbackController.CALLBACK_PATH;

        assertThat(matching(authInterceptors, callbackPath))
                .doesNotContain(userSessionInterceptor);
        assertThat(matching(networkInterceptors, callbackPath))
                .doesNotContain(networkRiskInterceptor);
        assertThat(matching(webRtcInterceptors, callbackPath))
                .doesNotContain(webRtcInterceptor);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> registered(WebMvcConfigurer configuration) {
        InterceptorRegistry registry = new InterceptorRegistry();
        configuration.addInterceptors(registry);
        return ReflectionTestUtils.invokeMethod(registry, "getInterceptors");
    }

    private static List<HandlerInterceptor> matching(
            List<Object> registrations,
            String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        ServletRequestPathUtils.parseAndCache(request);
        return registrations.stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(mapped -> mapped.matches(request))
                .map(MappedInterceptor::getInterceptor)
                .toList();
    }
}
