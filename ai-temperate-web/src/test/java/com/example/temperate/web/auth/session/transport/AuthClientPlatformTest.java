package com.example.temperate.web.auth.session.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 验证认证客户端平台枚举的解析、默认值、显式传输能力及非法输入拦截。
 */
class AuthClientPlatformTest {

    @Test
    @DisplayName("ANDROID 平台识别为显式令牌传输")
    void androidPlatformUsesExplicitTokenTransport() {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader("ANDROID");
        assertThat(platform).isEqualTo(AuthClientPlatform.ANDROID);
        assertThat(platform.usesExplicitTokenTransport()).isTrue();
    }

    @Test
    @DisplayName("WECHAT_MINI_PROGRAM 平台识别为显式令牌传输")
    void wechatMiniProgramPlatformUsesExplicitTokenTransport() {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader("WECHAT_MINI_PROGRAM");
        assertThat(platform).isEqualTo(AuthClientPlatform.WECHAT_MINI_PROGRAM);
        assertThat(platform.usesExplicitTokenTransport()).isTrue();
    }

    @Test
    @DisplayName("WECHAT_MINI_PROGRAM 大小写与首尾空格不影响解析")
    void wechatMiniProgramCaseInsensitive() {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader("  wechat_mini_program  ");
        assertThat(platform).isEqualTo(AuthClientPlatform.WECHAT_MINI_PROGRAM);
    }

    @Test
    @DisplayName("H5 平台识别为 Cookie 传输")
    void h5PlatformUsesCookieTransport() {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader("H5");
        assertThat(platform).isEqualTo(AuthClientPlatform.H5);
        assertThat(platform.usesExplicitTokenTransport()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("缺失或空白的平台头默认兼容回退为 H5")
    void missingOrBlankHeaderDefaultsToH5(String header) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(header);
        assertThat(platform).isEqualTo(AuthClientPlatform.H5);
        assertThat(platform.usesExplicitTokenTransport()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"IOS", "FLUTTER", "DESKTOP", "unknown", "123"})
    @DisplayName("未知非空平台头抛出受控异常")
    void unknownPlatformThrowsException(String invalidHeader) {
        assertThatThrownBy(() -> AuthClientPlatform.fromHeader(invalidHeader))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported client platform: " + invalidHeader);
    }
}
