package com.example.temperate.web.auth.oauth.provider;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证浏览器 OAuth Provider 客户端通过不可变枚举注册表选择并拒绝重复实现。
 */
class OAuthProviderClientRegistryTest {

    @Test
    void shouldSelectProviderImplementation() {
        OAuthProviderClientStrategy google = strategy(OAuthProvider.GOOGLE);
        OAuthProviderClientRegistry registry = new OAuthProviderClientRegistry(
                Map.of("google", google));
        assertSame(google, registry.getRequired(OAuthProvider.GOOGLE));
    }

    @Test
    void shouldRejectDuplicateProviderImplementation() {
        assertThrows(IllegalStateException.class, () -> new OAuthProviderClientRegistry(
                Map.of("a", strategy(OAuthProvider.GITHUB),
                        "b", strategy(OAuthProvider.GITHUB))));
    }

    @Test
    void shouldRejectMissingProviderWithControlledError() {
        OAuthProviderClientRegistry registry = new OAuthProviderClientRegistry(Map.of());

        assertThrows(OAuthProviderException.class,
                () -> registry.getRequired(OAuthProvider.GOOGLE));
    }

    private static OAuthProviderClientStrategy strategy(OAuthProvider provider) {
        OAuthProviderClientStrategy strategy = mock(OAuthProviderClientStrategy.class);
        when(strategy.provider()).thenReturn(provider);
        return strategy;
    }
}
