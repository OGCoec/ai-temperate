package com.example.temperate.service.risk.preauth.service.impl;

import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.preauth.service.PreAuthNetworkSnapshotFactory;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 按 Cloudflare 边缘优先、供应商与本地 BIN 补缺的规则构造 PreAuth 网络快照。
 *
 * <p>风险来源与地理来源分开记录；本地 BIN 和默认结果只能提供地理降级，信用分来源统一记为
 * DEFAULT，禁止伪装成第三方评分。</p>
 */
@Service
public final class PreAuthNetworkSnapshotFactoryImpl
        implements PreAuthNetworkSnapshotFactory {

    private final NetworkRiskIdentifier identifier;

    public PreAuthNetworkSnapshotFactoryImpl(
            NetworkRiskIdentifier identifier) {
        this.identifier = Objects.requireNonNull(identifier);
    }

    @Override
    public PreAuthNetworkSnapshot merge(
            TrustedNetworkObservation observation,
            IpIntelligenceSnapshot intelligence) {
        boolean edgeGeo = hasText(observation.countryCode())
                || observation.asn() != null
                || observation.hasCoordinates();
        return new PreAuthNetworkSnapshot(
                identifier.identifyIp(observation.clientIp()),
                intelligence.trustScore(),
                hasText(observation.countryCode())
                        ? observation.countryCode()
                        : intelligence.countryCode(),
                observation.asn() != null
                        ? observation.asn()
                        : intelligence.asn(),
                observation.latitude() != null
                        ? observation.latitude()
                        : intelligence.latitude(),
                observation.longitude() != null
                        ? observation.longitude()
                        : intelligence.longitude(),
                intelligence.networkType(),
                intelligence.scoreIncludesNetworkRisk(),
                riskSource(intelligence.source()),
                edgeGeo
                        ? PreAuthGeoSource.CLOUDFLARE_EDGE
                        : geoSource(intelligence),
                observation.observedAt());
    }

    private static PreAuthRiskSource riskSource(
            IpIntelligenceSource source) {
        return switch (source) {
            case IP2LOCATION -> PreAuthRiskSource.IP2LOCATION;
            case IPING, IP2LOCATION_AND_IPING -> PreAuthRiskSource.IPING;
            case LOCAL_BIN, DEFAULT -> PreAuthRiskSource.DEFAULT;
        };
    }

    private static PreAuthGeoSource geoSource(
            IpIntelligenceSnapshot intelligence) {
        boolean hasGeo = hasText(intelligence.countryCode())
                || intelligence.asn() != null
                || intelligence.hasCoordinates();
        if (!hasGeo) {
            return PreAuthGeoSource.NONE;
        }
        return switch (intelligence.source()) {
            case IP2LOCATION, IP2LOCATION_AND_IPING ->
                    PreAuthGeoSource.IP2LOCATION;
            case IPING -> PreAuthGeoSource.IPING;
            case LOCAL_BIN -> PreAuthGeoSource.LOCAL_BIN;
            case DEFAULT -> PreAuthGeoSource.NONE;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
