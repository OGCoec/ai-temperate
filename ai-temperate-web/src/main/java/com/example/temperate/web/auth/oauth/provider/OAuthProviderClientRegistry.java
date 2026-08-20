package com.example.temperate.web.auth.oauth.provider;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将 Spring 聚合注入的浏览器 OAuth Provider 客户端转换为不可变枚举注册表。
 */
@Component
public final class OAuthProviderClientRegistry {

    private final Map<OAuthProvider, OAuthProviderClientStrategy> strategies;

    public OAuthProviderClientRegistry(Map<String, OAuthProviderClientStrategy> strategyBeans) {
        EnumMap<OAuthProvider, OAuthProviderClientStrategy> registered =
                new EnumMap<>(OAuthProvider.class);
        for (OAuthProviderClientStrategy strategy : strategyBeans.values()) {
            OAuthProviderClientStrategy previous = registered.put(strategy.provider(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate OAuth provider client: " + strategy.provider());
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    public OAuthProviderClientStrategy getRequired(OAuthProvider provider) {
        OAuthProviderClientStrategy strategy = strategies.get(provider);
        if (strategy == null) {
            throw new OAuthProviderException(
                    OAuthProviderErrorCode.AUTHORIZATION_REJECTED,
                    "OAuth provider is unsupported.");
        }
        return strategy;
    }
}
