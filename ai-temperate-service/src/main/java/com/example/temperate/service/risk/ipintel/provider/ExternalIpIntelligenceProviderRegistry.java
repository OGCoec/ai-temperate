package com.example.temperate.service.risk.ipintel.provider;

import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 在启动期收集并校验全部 IP 情报策略，为业务层提供按稳定枚举查找的不可变注册表。
 */
@Component
public final class ExternalIpIntelligenceProviderRegistry {

    private final Map<ExternalIpProviderType, ExternalIpIntelligenceProvider> providers;

    public ExternalIpIntelligenceProviderRegistry(
            Map<String, ExternalIpIntelligenceProvider> providerBeans) {
        EnumMap<ExternalIpProviderType, ExternalIpIntelligenceProvider> registered =
                new EnumMap<>(ExternalIpProviderType.class);
        for (ExternalIpIntelligenceProvider provider : providerBeans.values()) {
            ExternalIpIntelligenceProvider previous =
                    registered.put(provider.type(), Objects.requireNonNull(provider));
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate IP intelligence provider: " + provider.type());
            }
        }
        for (ExternalIpProviderType required : ExternalIpProviderType.values()) {
            if (!registered.containsKey(required)) {
                throw new IllegalStateException(
                        "Missing IP intelligence provider: " + required);
            }
        }
        this.providers = Map.copyOf(registered);
    }

    public ExternalIpIntelligenceProvider getRequired(ExternalIpProviderType type) {
        ExternalIpIntelligenceProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported IP intelligence provider: " + type);
        }
        return provider;
    }
}
