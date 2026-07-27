package com.example.temperate.service.risk.preauth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证 PreAuth 当前快照优先采用可信 Cloudflare 地理字段，同时保留实际信用分供应商来源。
 */
class PreAuthNetworkSnapshotFactoryImplTest {

    private static final Instant NOW =
            Instant.parse("2026-07-25T12:00:00Z");
    private static final PreAuthNetworkSnapshotFactoryImpl FACTORY =
            new PreAuthNetworkSnapshotFactoryImpl(new NetworkRiskIdentifier(
                    new HmacSha256Identifier(
                            "snapshot-factory-test-secret-0123456789"
                                    .getBytes())));

    @Test
    void cloudflareGeoOverridesProviderGeoWithoutChangingRiskSource() {
        var snapshot = FACTORY.merge(
                new TrustedNetworkObservation(
                        "198.51.100.10",
                        "US",
                        8075L,
                        new BigDecimal("41.85003"),
                        new BigDecimal("-87.65005"),
                        NOW),
                intelligence(
                        IpIntelligenceSource.IP2LOCATION,
                        "GB",
                        "51.5074",
                        "-0.1278"));

        assertThat(snapshot.countryCode()).isEqualTo("US");
        assertThat(snapshot.asn()).isEqualTo(8075L);
        assertThat(snapshot.latitude())
                .isEqualByComparingTo("41.85003");
        assertThat(snapshot.longitude())
                .isEqualByComparingTo("-87.65005");
        assertThat(snapshot.riskSource())
                .isEqualTo(PreAuthRiskSource.IP2LOCATION);
        assertThat(snapshot.geoSource())
                .isEqualTo(PreAuthGeoSource.CLOUDFLARE_EDGE);
        assertThat(snapshot.observedAt()).isEqualTo(NOW);
    }

    @Test
    void missingEdgeGeoFallsBackToLocalBinAndDefaultRisk() {
        var snapshot = FACTORY.merge(
                new TrustedNetworkObservation(
                        "198.51.100.10",
                        null,
                        null,
                        null,
                        null,
                        NOW),
                intelligence(
                        IpIntelligenceSource.LOCAL_BIN,
                        "US",
                        "41.85003",
                        "-87.65005"));

        assertThat(snapshot.countryCode()).isEqualTo("US");
        assertThat(snapshot.riskSource())
                .isEqualTo(PreAuthRiskSource.DEFAULT);
        assertThat(snapshot.geoSource())
                .isEqualTo(PreAuthGeoSource.LOCAL_BIN);
    }

    private static IpIntelligenceSnapshot intelligence(
            IpIntelligenceSource source,
            String country,
            String latitude,
            String longitude) {
        return new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                80,
                country,
                64500L,
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                NetworkType.RESIDENTIAL,
                source == IpIntelligenceSource.IP2LOCATION,
                source);
    }
}
