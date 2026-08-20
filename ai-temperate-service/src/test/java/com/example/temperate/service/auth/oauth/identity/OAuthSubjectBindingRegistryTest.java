package com.example.temperate.service.auth.oauth.identity;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 OAuth Subject 绑定策略注册表会拒绝重复类型并精确选择 Provider 实现。
 */
class OAuthSubjectBindingRegistryTest {

    @Test
    void shouldSelectStrategyByStableProviderEnum() {
        OAuthSubjectBindingStrategy github = strategy(OAuthProvider.GITHUB);
        OAuthSubjectBindingStrategy google = strategy(OAuthProvider.GOOGLE);
        OAuthSubjectBindingRegistry registry = new OAuthSubjectBindingRegistry(
                Map.of("github", github, "google", google));

        assertSame(github, registry.getRequired(OAuthProvider.GITHUB));
        assertSame(google, registry.getRequired(OAuthProvider.GOOGLE));
    }

    @Test
    void shouldFailFastForDuplicateProvider() {
        assertThrows(IllegalStateException.class, () -> new OAuthSubjectBindingRegistry(
                Map.of("first", strategy(OAuthProvider.GITHUB),
                        "second", strategy(OAuthProvider.GITHUB))));
    }

    @Test
    void shouldRejectUnknownProvider() {
        OAuthSubjectBindingRegistry registry = new OAuthSubjectBindingRegistry(Map.of());
        assertThrows(OAuthAccountException.class,
                () -> registry.getRequired(OAuthProvider.GOOGLE));
    }

    private static OAuthSubjectBindingStrategy strategy(OAuthProvider provider) {
        OAuthSubjectBindingStrategy strategy = mock(OAuthSubjectBindingStrategy.class);
        when(strategy.provider()).thenReturn(provider);
        return strategy;
    }
}
