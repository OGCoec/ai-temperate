package com.example.temperate.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.config.AdminConfigurationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证管理员配置状态 MVC 拦截器保持原公开路径、首次注册状态门和受保护路径 Fail Closed 语义。
 */
class AdminConfigurationStateInterceptorTest {

    private AdminConfigurationService configurationService;
    private AdminConfigurationStateInterceptor interceptor;

    @BeforeEach
    void setUp() {
        configurationService = mock(AdminConfigurationService.class);
        interceptor = new AdminConfigurationStateInterceptor(configurationService);
    }

    @Test
    void publicPathsBypassConfigurationState() {
        for (String path : List.of(
                "/api/admin/auth/state",
                "/api/admin/_edge/pre-auth",
                "/api/admin/_edge/risk-challenge",
                "/api/admin/_edge/webrtc/start",
                "/api/admin/_edge/webrtc/report",
                "/api/admin/auth/phone-country",
                "/api/admin/auth/hcaptcha/config",
                "/api/admin/auth/hcaptcha/page",
                "/api/admin/auth/hcaptcha/page.css",
                "/api/admin/auth/hcaptcha/page.js")) {
            assertThat(preHandle(path)).isTrue();
        }

        verifyNoInteractions(configurationService);
    }

    @Test
    void registrationRootAndChildrenRequireUninitializedConfiguration() {
        assertThat(preHandle("/api/admin/auth/register")).isTrue();
        assertThat(preHandle("/api/admin/auth/register/start")).isTrue();

        verify(configurationService, times(2))
                .requireUninitialized();
    }

    @Test
    void protectedAdministratorPathRequiresActiveConfiguration() {
        assertThat(preHandle("/api/admin/auth/session/bootstrap")).isTrue();

        verify(configurationService).requireActive();
    }

    @Test
    void configurationFailurePropagatesToMvcExceptionHandling() {
        AdminException failure = new AdminException(
                AdminErrorCode.ADMIN_CONFIG_INVALID,
                "invalid administrator configuration");
        when(configurationService.requireActive()).thenThrow(failure);

        assertThatThrownBy(() -> preHandle("/api/admin/me"))
                .isSameAs(failure);
    }

    @Test
    void nonAdministratorPathNeverUsesAdministratorConfiguration() {
        assertThat(preHandle("/api/auth/session")).isTrue();

        verifyNoInteractions(configurationService);
    }

    private boolean preHandle(String path) {
        return interceptor.preHandle(
                new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(),
                new Object());
    }
}
