package com.example.temperate.service.risk.ipintel.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 IP 情报多实现注册表在启动时拒绝重复和缺失策略，并按稳定枚举返回实现。
 */
class ExternalIpIntelligenceProviderRegistryTest {

    @Test
    void registersEveryRequiredProviderByStableType() {
        ExternalIpIntelligenceProvider ip2Location = provider(
                ExternalIpProviderType.IP2LOCATION);
        ExternalIpIntelligenceProvider iping = provider(ExternalIpProviderType.IPING);

        ExternalIpIntelligenceProviderRegistry registry =
                new ExternalIpIntelligenceProviderRegistry(Map.of(
                        "ip2Location", ip2Location,
                        "iping", iping));

        assertThat(registry.getRequired(ExternalIpProviderType.IP2LOCATION))
                .isSameAs(ip2Location);
        assertThat(registry.getRequired(ExternalIpProviderType.IPING))
                .isSameAs(iping);
    }

    @Test
    void rejectsDuplicateAndMissingProviderTypes() {
        ExternalIpIntelligenceProvider first = provider(
                ExternalIpProviderType.IP2LOCATION);
        ExternalIpIntelligenceProvider duplicate = provider(
                ExternalIpProviderType.IP2LOCATION);

        assertThatThrownBy(() -> new ExternalIpIntelligenceProviderRegistry(Map.of(
                "first", first,
                "duplicate", duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new ExternalIpIntelligenceProviderRegistry(Map.of(
                "only", first)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing");
    }

    private static ExternalIpIntelligenceProvider provider(
            ExternalIpProviderType type) {
        ExternalIpIntelligenceProvider provider =
                mock(ExternalIpIntelligenceProvider.class);
        when(provider.type()).thenReturn(type);
        return provider;
    }
}
