package com.example.temperate.service.risk.ipintel.domain;

import java.math.BigDecimal;

/**
 * 承载单个外部供应商的受控结果，既允许完整成功，也允许只有地理信息的部分成功。
 */
public record ProviderIpIntelligenceResult(
        ExternalIpProviderType provider,
        boolean success,
        ProviderFailureType failureType,
        Integer trustScore,
        String countryCode,
        Long asn,
        BigDecimal latitude,
        BigDecimal longitude,
        NetworkType networkType,
        boolean scoreIncludesNetworkRisk) {

    public static ProviderIpIntelligenceResult failed(
            ExternalIpProviderType provider,
            ProviderFailureType failureType) {
        return new ProviderIpIntelligenceResult(
                provider,
                false,
                failureType,
                null,
                null,
                null,
                null,
                null,
                NetworkType.UNKNOWN,
                false);
    }

    public boolean hasTrustScore() {
        return trustScore != null;
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
