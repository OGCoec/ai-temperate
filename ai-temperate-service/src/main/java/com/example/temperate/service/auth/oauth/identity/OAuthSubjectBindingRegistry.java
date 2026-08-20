package com.example.temperate.service.auth.oauth.identity;

import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将 Spring 聚合注入的 OAuth Subject 策略转换为不可变 Provider 注册表。
 */
@Component
public final class OAuthSubjectBindingRegistry {

    private final Map<OAuthProvider, OAuthSubjectBindingStrategy> strategies;

    public OAuthSubjectBindingRegistry(Map<String, OAuthSubjectBindingStrategy> strategyBeans) {
        EnumMap<OAuthProvider, OAuthSubjectBindingStrategy> registered =
                new EnumMap<>(OAuthProvider.class);
        for (OAuthSubjectBindingStrategy strategy : strategyBeans.values()) {
            OAuthSubjectBindingStrategy previous = registered.put(strategy.provider(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate OAuth subject strategy: " + strategy.provider());
            }
        }
        this.strategies = Map.copyOf(registered);
    }

    public OAuthSubjectBindingStrategy getRequired(OAuthProvider provider) {
        OAuthSubjectBindingStrategy strategy = strategies.get(provider);
        if (strategy == null) {
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.INVALID_IDENTITY,
                    "OAuth provider is unsupported.");
        }
        return strategy;
    }
}
