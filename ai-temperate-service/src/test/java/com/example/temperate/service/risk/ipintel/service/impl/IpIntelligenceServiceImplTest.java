package com.example.temperate.service.risk.ipintel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ipintel.cache.IpIntelligenceCache;
import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.domain.ProviderFailureType;
import com.example.temperate.service.risk.ipintel.domain.ProviderIpIntelligenceResult;
import com.example.temperate.service.risk.ipintel.local.LocalIpGeoProvider;
import com.example.temperate.service.risk.ipintel.local.LocalIpGeoResult;
import com.example.temperate.service.risk.ipintel.provider.ExternalIpIntelligenceProvider;
import com.example.temperate.service.risk.ipintel.provider.ExternalIpIntelligenceProviderRegistry;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * 验证 IP 情报编排服务的缓存优先、供应商补全、短 TTL 降级和总等待预算。
 */
class IpIntelligenceServiceImplTest {

    private static final String IP = "198.51.100.10";

    @Test
    void anyReadableRedisEntryIncludingPermanentKeySkipsProvidersAndLocalDatabase() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        IpIntelligenceSnapshot cached = snapshot(
                88,
                IpIntelligenceSource.IP2LOCATION,
                "US",
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"));
        when(fixture.cache().find(any())).thenReturn(Optional.of(cached));

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();

        assertThat(lookup.snapshot()).isSameAs(cached);
        assertThat(lookup.initialCacheHit()).isTrue();
        verifyNoInteractions(fixture.providers(), fixture.localGeoProvider());
    }

    @Test
    void singleFlightWaiterKeepsInitialCacheMissSemantics() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        IpIntelligenceSnapshot ownerResult = snapshot(
                76,
                IpIntelligenceSource.IP2LOCATION,
                "US",
                new BigDecimal("41.8781"),
                new BigDecimal("-87.6298"));
        when(fixture.cache().find(any()))
                .thenReturn(Optional.empty(), Optional.of(ownerResult));
        when(fixture.cache().tryAcquireLookup(any(), any(), any()))
                .thenReturn(false);

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();

        assertThat(lookup.snapshot()).isSameAs(ownerResult);
        assertThat(lookup.initialCacheHit()).isFalse();
        verifyNoInteractions(fixture.providers(), fixture.localGeoProvider());
    }

    @Test
    void completeIp2LocationResultSkipsIpingAndUsesPositiveCacheTtl() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        ExternalIpIntelligenceProvider ip2 = provider(
                ExternalIpProviderType.IP2LOCATION,
                result(
                        ExternalIpProviderType.IP2LOCATION,
                        91,
                        "US",
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298")));
        ExternalIpIntelligenceProvider iping = mock(ExternalIpIntelligenceProvider.class);
        when(fixture.providers().getRequired(ExternalIpProviderType.IP2LOCATION))
                .thenReturn(ip2);
        when(fixture.providers().getRequired(ExternalIpProviderType.IPING))
                .thenReturn(iping);

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();
        IpIntelligenceSnapshot result = lookup.snapshot();

        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(result.trustScore()).isEqualTo(91);
        assertThat(result.source()).isEqualTo(IpIntelligenceSource.IP2LOCATION);
        verify(iping, never()).query(any());
        assertStoredTtlBetween(
                fixture.cache(),
                Duration.ofHours(4).plusMinutes(48),
                Duration.ofHours(7).plusMinutes(12));
    }

    @Test
    void cacheReadFailureStillQueriesIp2Location() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        when(fixture.cache().find(any())).thenThrow(
                new IllegalStateException("redis read unavailable"));
        ExternalIpIntelligenceProvider ip2 = provider(
                ExternalIpProviderType.IP2LOCATION,
                result(
                        ExternalIpProviderType.IP2LOCATION,
                        91,
                        "US",
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298")));
        when(fixture.providers().getRequired(ExternalIpProviderType.IP2LOCATION))
                .thenReturn(ip2);

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();

        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(lookup.snapshot().source()).isEqualTo(IpIntelligenceSource.IP2LOCATION);
        verify(ip2).query(IP);
    }

    @Test
    void singleFlightCoordinationFailureStillQueriesIp2LocationWithoutRelease() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        when(fixture.cache().tryAcquireLookup(any(), any(), any())).thenThrow(
                new IllegalStateException("redis coordination unavailable"));
        ExternalIpIntelligenceProvider ip2 = provider(
                ExternalIpProviderType.IP2LOCATION,
                result(
                        ExternalIpProviderType.IP2LOCATION,
                        91,
                        "US",
                        new BigDecimal("41.8781"),
                        new BigDecimal("-87.6298")));
        when(fixture.providers().getRequired(ExternalIpProviderType.IP2LOCATION))
                .thenReturn(ip2);

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();

        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(lookup.snapshot().source()).isEqualTo(IpIntelligenceSource.IP2LOCATION);
        verify(ip2).query(IP);
        verify(fixture.cache(), never()).releaseLookup(any(), any());
    }

    @Test
    void partialIp2LocationGeoIsCombinedWithIpingScore() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        ProviderIpIntelligenceResult partialIp2 = new ProviderIpIntelligenceResult(
                ExternalIpProviderType.IP2LOCATION,
                true,
                ProviderFailureType.NONE,
                null,
                "GB",
                64500L,
                new BigDecimal("51.5074"),
                new BigDecimal("-0.1278"),
                NetworkType.RESIDENTIAL,
                false);
        ExternalIpIntelligenceProvider ip2 =
                provider(ExternalIpProviderType.IP2LOCATION, partialIp2);
        ExternalIpIntelligenceProvider iping = provider(
                ExternalIpProviderType.IPING,
                result(
                        ExternalIpProviderType.IPING,
                        72,
                        null,
                        null,
                        null));
        when(fixture.providers().getRequired(ExternalIpProviderType.IP2LOCATION))
                .thenReturn(ip2);
        when(fixture.providers().getRequired(ExternalIpProviderType.IPING))
                .thenReturn(iping);

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();
        IpIntelligenceSnapshot result = lookup.snapshot();

        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(result.trustScore()).isEqualTo(72);
        assertThat(result.countryCode()).isEqualTo("GB");
        assertThat(result.latitude()).isEqualByComparingTo("51.5074");
        assertThat(result.source())
                .isEqualTo(IpIntelligenceSource.IP2LOCATION_AND_IPING);
    }

    @Test
    void providerFailuresAssignZeroTrustScoreAndUseShortFallbackCacheTtl() {
        Fixture fixture = fixture(Duration.ofSeconds(8));
        when(fixture.localGeoProvider().findGeo(IP))
                .thenReturn(Optional.of(new LocalIpGeoResult(
                        "CA",
                        64501L,
                        new BigDecimal("43.6532"),
                        new BigDecimal("-79.3832"))));
        ExternalIpIntelligenceProvider ip2 = provider(
                ExternalIpProviderType.IP2LOCATION,
                ProviderIpIntelligenceResult.failed(
                        ExternalIpProviderType.IP2LOCATION,
                        ProviderFailureType.UNAVAILABLE));
        ExternalIpIntelligenceProvider iping = provider(
                ExternalIpProviderType.IPING,
                ProviderIpIntelligenceResult.failed(
                        ExternalIpProviderType.IPING,
                        ProviderFailureType.UNAVAILABLE));
        when(fixture.providers().getRequired(ExternalIpProviderType.IP2LOCATION))
                .thenReturn(ip2);
        when(fixture.providers().getRequired(ExternalIpProviderType.IPING))
                .thenReturn(iping);

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();
        IpIntelligenceSnapshot result = lookup.snapshot();

        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(result.trustScore()).isZero();
        assertThat(result.countryCode()).isEqualTo("CA");
        assertThat(result.source()).isEqualTo(IpIntelligenceSource.LOCAL_BIN);
        assertStoredTtlBetween(
                fixture.cache(),
                Duration.ofSeconds(24),
                Duration.ofSeconds(36));
    }

    @Test
    void externalProviderCannotExceedAbsoluteLookupBudget() {
        Fixture fixture = fixture(Duration.ofMillis(100));
        ExternalIpIntelligenceProvider neverCompletes =
                mock(ExternalIpIntelligenceProvider.class);
        when(neverCompletes.query(IP)).thenReturn(Mono.never());
        when(fixture.providers().getRequired(ExternalIpProviderType.IP2LOCATION))
                .thenReturn(neverCompletes);

        IpIntelligenceLookupResult lookup = assertTimeoutPreemptively(
                Duration.ofSeconds(1),
                () -> fixture.service().lookup(IP).block());

        IpIntelligenceSnapshot result = lookup.snapshot();
        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(result.trustScore()).isZero();
        assertThat(result.source()).isEqualTo(IpIntelligenceSource.DEFAULT);
        assertStoredTtlBetween(
                fixture.cache(),
                Duration.ofSeconds(24),
                Duration.ofSeconds(36));
    }

    @Test
    void exhaustedBulkheadFallsBackWithoutCallingAProvider() {
        Fixture fixture = fixture(Duration.ofSeconds(8), new Semaphore(0));

        IpIntelligenceLookupResult lookup = fixture.service().lookup(IP).block();
        IpIntelligenceSnapshot result = lookup.snapshot();

        assertThat(lookup.initialCacheHit()).isFalse();
        assertThat(result.trustScore()).isZero();
        assertThat(result.source()).isEqualTo(IpIntelligenceSource.DEFAULT);
        verifyNoInteractions(fixture.providers());
        assertStoredTtlBetween(
                fixture.cache(),
                Duration.ofSeconds(24),
                Duration.ofSeconds(36));
    }

    private static Fixture fixture(Duration lookupTimeout) {
        return fixture(lookupTimeout, new Semaphore(32));
    }

    private static Fixture fixture(
            Duration lookupTimeout,
            Semaphore bulkhead) {
        IpIntelligenceCache cache = mock(IpIntelligenceCache.class);
        when(cache.find(any())).thenReturn(Optional.empty());
        when(cache.tryAcquireLookup(any(), any(), any())).thenReturn(true);
        ExternalIpIntelligenceProviderRegistry providers =
                mock(ExternalIpIntelligenceProviderRegistry.class);
        LocalIpGeoProvider localGeoProvider = mock(LocalIpGeoProvider.class);
        when(localGeoProvider.findGeo(any())).thenReturn(Optional.empty());
        NetworkRiskIdentifier identifier = new NetworkRiskIdentifier(
                new HmacSha256Identifier(
                        "ip-intelligence-test-secret-0123456789".getBytes()));
        NetworkRiskProperties properties = properties(lookupTimeout);
        IpIntelligenceServiceImpl service = new IpIntelligenceServiceImpl(
                identifier,
                cache,
                providers,
                localGeoProvider,
                properties,
                bulkhead,
                new NetworkRiskMetrics(new SimpleMeterRegistry()));
        return new Fixture(service, cache, providers, localGeoProvider);
    }

    private static ExternalIpIntelligenceProvider provider(
            ExternalIpProviderType type,
            ProviderIpIntelligenceResult result) {
        ExternalIpIntelligenceProvider provider =
                mock(ExternalIpIntelligenceProvider.class);
        when(provider.type()).thenReturn(type);
        when(provider.query(IP)).thenReturn(Mono.just(result));
        return provider;
    }

    private static ProviderIpIntelligenceResult result(
            ExternalIpProviderType provider,
            int trustScore,
            String country,
            BigDecimal latitude,
            BigDecimal longitude) {
        return new ProviderIpIntelligenceResult(
                provider,
                true,
                ProviderFailureType.NONE,
                trustScore,
                country,
                64500L,
                latitude,
                longitude,
                NetworkType.RESIDENTIAL,
                true);
    }

    private static IpIntelligenceSnapshot snapshot(
            int trustScore,
            IpIntelligenceSource source,
            String country,
            BigDecimal latitude,
            BigDecimal longitude) {
        return new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                trustScore,
                country,
                64500L,
                latitude,
                longitude,
                NetworkType.RESIDENTIAL,
                true,
                source);
    }

    private static void assertStoredTtlBetween(
            IpIntelligenceCache cache,
            Duration minimum,
            Duration maximum) {
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(cache).store(any(), any(), ttl.capture());
        assertThat(ttl.getValue()).isBetween(minimum, maximum);
    }

    private static NetworkRiskProperties properties(Duration lookupTimeout) {
        String secret = Base64.getEncoder().encodeToString(
                "network-risk-properties-test-0123456789".getBytes());
        return new NetworkRiskProperties(
                NetworkRiskMode.ENFORCE,
                secret,
                secret,
                URI.create("https://api.ip2location.test/"),
                URI.create("https://api.iping.test/"),
                true,
                lookupTimeout,
                Duration.ofHours(6),
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                32,
                Duration.ofMinutes(30),
                Duration.ofHours(6),
                Duration.ofMinutes(5),
                Duration.ofMinutes(30),
                200D,
                Duration.ofHours(24),
                Duration.ofMinutes(10),
                webRtc(secret));
    }

    private static NetworkRiskProperties.WebRtc webRtc(String secret) {
        return new NetworkRiskProperties.WebRtc(
                Duration.ofSeconds(8),
                Duration.ofSeconds(12),
                Duration.ofSeconds(3),
                List.of(
                        URI.create("stun:stun.l.google.com:19302"),
                        URI.create("stun:stun.cloudflare.com:3478"),
                        URI.create("stun:global.stun.twilio.com:3478"),
                        URI.create("stun:stun.nextcloud.com:3478")),
                8,
                secret);
    }

    private record Fixture(
            IpIntelligenceServiceImpl service,
            IpIntelligenceCache cache,
            ExternalIpIntelligenceProviderRegistry providers,
            LocalIpGeoProvider localGeoProvider) {
    }
}
