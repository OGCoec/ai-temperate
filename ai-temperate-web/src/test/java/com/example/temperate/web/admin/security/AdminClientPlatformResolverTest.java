package com.example.temperate.web.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 验证浏览器不能仅伪造平台 Header 切换到返回原始 Token 的 Android 传输协议。
 */
class AdminClientPlatformResolverTest {

    private final AdminClientPlatformResolver resolver =
            new AdminClientPlatformResolver();

    @Test
    void browserOriginForcesH5EvenWhenAndroidHeaderIsForged() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");
        request.addHeader("Origin", "https://admin.niko000o.site");

        assertThat(resolver.resolve(request)).isEqualTo(AuthClientPlatform.H5);
    }

    @Test
    void originlessNativeRequestMayUseAndroidTransport() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Client-Platform", "ANDROID");

        assertThat(resolver.resolve(request)).isEqualTo(AuthClientPlatform.ANDROID);
    }
}
