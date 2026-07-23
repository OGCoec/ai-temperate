package com.example.temperate.web.auth.device.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.device.exception.GlobalDeviceBlockInfrastructureException;
import com.example.temperate.service.auth.device.service.GlobalDeviceBlockService;
import com.example.temperate.web.auth.device.exception.GlobalDeviceBlockException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证全局设备封禁拦截器会在 MVC 处理器执行前拦截认证入口，并转换为统一 JSON 响应。
 */
class GlobalDeviceBlockInterceptorTest {

    private static final String DEVICE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private GlobalDeviceBlockService blockService;
    private GlobalDeviceBlockInterceptor interceptor;
    private Object handler;

    @BeforeEach
    void setUp() {
        blockService = mock(GlobalDeviceBlockService.class);
        interceptor = new GlobalDeviceBlockInterceptor(blockService);
        handler = new Object();
    }

    @Test
    void marksTheFullConstructorAsTheSpringInjectionConstructor() throws Exception {
        Constructor<GlobalDeviceBlockInterceptor> constructor =
                GlobalDeviceBlockInterceptor.class.getConstructor(
                        GlobalDeviceBlockService.class,
                        ObjectMapper.class,
                        Clock.class);

        assertThat(constructor).isNotNull();
        assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue();
    }

    @Test
    void rejectsBlockedLoginRequestWithRetryAfter() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/login/password");
        request.addHeader("X-Device-Installation-Id", DEVICE_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(blockService.remainingBlockTtl(DEVICE_ID)).thenReturn(Duration.ofSeconds(42));

        boolean allowed = interceptor.preHandle(request, response, handler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("42");
        assertThat(response.getContentAsString())
                .contains("\"code\":\"DEVICE_BLOCKED\"");
    }

    @Test
    void allowsUnblockedRefreshRequest() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/session/refresh");
        request.addHeader("X-Device-Installation-Id", DEVICE_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(blockService.remainingBlockTtl(DEVICE_ID)).thenReturn(Duration.ZERO);

        boolean allowed = interceptor.preHandle(request, response, handler);

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingDeviceHeaderOnProtectedRequest() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/password-reset/start");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(blockService.remainingBlockTtl(null))
                .thenThrow(GlobalDeviceBlockException.invalidInput());

        boolean allowed = interceptor.preHandle(request, response, handler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_INPUT\"");
    }

    @Test
    void rejectsInvalidDeviceUuidOnProtectedRequest() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/register/start");
        request.addHeader("X-Device-Installation-Id", " 550e8400-e29b-41d4-a716-446655440000 ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(blockService.remainingBlockTtl(" 550e8400-e29b-41d4-a716-446655440000 "))
                .thenThrow(new IllegalArgumentException("invalid device"));

        boolean allowed = interceptor.preHandle(request, response, handler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INVALID_INPUT\"");
    }

    @Test
    void mapsRedisFailureToUnavailable() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/auth/session/bootstrap");
        request.addHeader("X-Device-Installation-Id", DEVICE_ID);
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(blockService.remainingBlockTtl(DEVICE_ID))
                .thenThrow(new GlobalDeviceBlockInfrastructureException(
                        "redis unavailable", new IllegalStateException("down")));

        boolean allowed = interceptor.preHandle(request, response, handler);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"INFRASTRUCTURE_UNAVAILABLE\"");
    }

    @Test
    void skipsLogoutOptionsAndUnprotectedRequests() throws Exception {
        MockHttpServletRequest logout = request("POST", "/api/auth/session/logout");
        MockHttpServletResponse logoutResponse = new MockHttpServletResponse();
        MockHttpServletRequest options = request("OPTIONS", "/api/auth/login/password");
        MockHttpServletResponse optionsResponse = new MockHttpServletResponse();
        MockHttpServletRequest health = request("GET", "/api/health");
        MockHttpServletResponse healthResponse = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(logout, logoutResponse, handler)).isTrue();
        assertThat(interceptor.preHandle(options, optionsResponse, handler)).isTrue();
        assertThat(interceptor.preHandle(health, healthResponse, handler)).isTrue();

        verifyNoInteractions(blockService);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }
}
