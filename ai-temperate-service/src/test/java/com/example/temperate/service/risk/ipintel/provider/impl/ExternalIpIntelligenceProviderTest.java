package com.example.temperate.service.risk.ipintel.provider.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.config.NetworkRiskMode;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ip2location.domain.AcquiredIp2LocationKey;
import com.example.temperate.service.risk.ip2location.service.Ip2LocationApiKeyService;
import com.example.temperate.service.risk.ipintel.domain.ProviderFailureType;
import com.example.temperate.service.risk.ipintel.domain.ProviderIpIntelligenceResult;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 验证 IP2Location 与 iPing 的异步解析、评分方向转换、凭据传输和 IPv6 降级边界。
 */
class ExternalIpIntelligenceProviderTest {

    @Test
    void ip2LocationConvertsProviderRiskAndUsesBearerAuthorization() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        Ip2LocationApiKeyService keys = mock(Ip2LocationApiKeyService.class);
        when(keys.acquire()).thenReturn(Optional.of(new AcquiredIp2LocationKey(
                HmacIdentifier.fromProtectedValue(
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                "test-api-key",
                99)));
        WebClient client = client(captured, """
                {
                  "fraud_score": 40,
                  "country_code": "US",
                  "asn": "AS64500",
                  "latitude": 41.8781,
                  "longitude": -87.6298
                }
                """);
        Ip2LocationIpIntelligenceProvider provider =
                new Ip2LocationIpIntelligenceProvider(
                        keys,
                        client,
                        properties(true),
                        metrics());

        ProviderIpIntelligenceResult result =
                provider.query("203.0.113.10").block(Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.trustScore()).isEqualTo(60);
        assertThat(result.countryCode()).isEqualTo("US");
        assertThat(result.asn()).isEqualTo(64500L);
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer test-api-key");
        assertThat(captured.get().url().getQuery()).contains("ip=203.0.113.10");
    }

    @Test
    void ipingConvertsRiskAndSkipsIpv6WithoutCallingNetwork() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        IpingIpIntelligenceProvider provider =
                new IpingIpIntelligenceProvider(
                        client(captured, """
                                {
                                  "code": 200,
                                  "data": {
                                    "risk_score": 80,
                                    "country_code": "GB"
                                  }
                                }
                                """),
                        properties(true),
                        metrics());

        ProviderIpIntelligenceResult ipv4 =
                provider.query("198.51.100.20").block(Duration.ofSeconds(1));
        assertThat(ipv4).isNotNull();
        assertThat(ipv4.trustScore()).isEqualTo(20);
        assertThat(ipv4.countryCode()).isEqualTo("GB");

        captured.set(null);
        ProviderIpIntelligenceResult ipv6 =
                provider.query("2001:db8::1").block(Duration.ofSeconds(1));
        assertThat(ipv6).isNotNull();
        assertThat(ipv6.failureType()).isEqualTo(ProviderFailureType.INVALID_INPUT);
        assertThat(captured.get()).isNull();
    }

    @Test
    void ip2LocationDiscardsOnlyExplicitlyRejectedOrExhaustedKeys() {
        for (HttpStatus status : new HttpStatus[] {
                HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN,
                HttpStatus.TOO_MANY_REQUESTS
        }) {
            Ip2LocationApiKeyService keys = mock(Ip2LocationApiKeyService.class);
            HmacIdentifier keyId = HmacIdentifier.fromProtectedValue(
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            when(keys.acquire()).thenReturn(Optional.of(
                    new AcquiredIp2LocationKey(keyId, "test-api-key", 99)));
            Ip2LocationIpIntelligenceProvider provider =
                    new Ip2LocationIpIntelligenceProvider(
                            keys,
                            statusClient(status, "{}"),
                            properties(true),
                            metrics());

            ProviderIpIntelligenceResult result =
                    provider.query("203.0.113.10").block(Duration.ofSeconds(1));

            assertThat(result).isNotNull();
            assertThat(result.failureType()).isIn(
                    ProviderFailureType.AUTHENTICATION,
                    ProviderFailureType.QUOTA_EXHAUSTED);
            verify(keys).discard(keyId);
        }
    }

    @Test
    void ip2LocationFiveHundredDoesNotDeletePotentiallyHealthyKey() {
        Ip2LocationApiKeyService keys = mock(Ip2LocationApiKeyService.class);
        HmacIdentifier keyId = HmacIdentifier.fromProtectedValue(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        when(keys.acquire()).thenReturn(Optional.of(
                new AcquiredIp2LocationKey(keyId, "test-api-key", 99)));
        Ip2LocationIpIntelligenceProvider provider =
                new Ip2LocationIpIntelligenceProvider(
                        keys,
                        statusClient(HttpStatus.INTERNAL_SERVER_ERROR, "{}"),
                        properties(true),
                        metrics());

        ProviderIpIntelligenceResult result =
                provider.query("203.0.113.10").block(Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.failureType()).isEqualTo(ProviderFailureType.HTTP_STATUS);
        verify(keys, never()).discard(keyId);
    }

    @Test
    void providersReturnControlledTimeoutInsteadOfThrowing() {
        Ip2LocationApiKeyService keys = mock(Ip2LocationApiKeyService.class);
        when(keys.acquire()).thenReturn(Optional.of(new AcquiredIp2LocationKey(
                HmacIdentifier.fromProtectedValue(
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
                "test-api-key",
                99)));
        WebClient neverCompletes = WebClient.builder()
                .baseUrl("https://provider.test/")
                .exchangeFunction(request -> Mono.never())
                .build();
        Ip2LocationIpIntelligenceProvider ip2 =
                new Ip2LocationIpIntelligenceProvider(
                        keys,
                        neverCompletes,
                        properties(true, Duration.ofMillis(100)),
                        metrics());
        IpingIpIntelligenceProvider iping =
                new IpingIpIntelligenceProvider(
                        neverCompletes,
                        properties(true, Duration.ofMillis(100)),
                        metrics());

        ProviderIpIntelligenceResult ip2Result =
                ip2.query("203.0.113.10").block(Duration.ofSeconds(1));
        ProviderIpIntelligenceResult ipingResult =
                iping.query("203.0.113.10").block(Duration.ofSeconds(1));

        assertThat(ip2Result.failureType()).isEqualTo(ProviderFailureType.TIMEOUT);
        assertThat(ipingResult.failureType()).isEqualTo(ProviderFailureType.TIMEOUT);
    }

    @Test
    void ipingPreservesPartialGeoWhenRiskScoreIsMissing() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        IpingIpIntelligenceProvider provider =
                new IpingIpIntelligenceProvider(
                        client(captured, """
                                {
                                  "code": 200,
                                  "data": {
                                    "country_code": "CA",
                                    "latitude": 43.6532,
                                    "longitude": -79.3832
                                  }
                                }
                                """),
                        properties(true),
                        metrics());

        ProviderIpIntelligenceResult result =
                provider.query("198.51.100.20").block(Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.trustScore()).isNull();
        assertThat(result.countryCode()).isEqualTo("CA");
    }

    private static WebClient client(
            AtomicReference<ClientRequest> captured,
            String body) {
        return WebClient.builder()
                .baseUrl("https://provider.test/")
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(
                                    HttpHeaders.CONTENT_TYPE,
                                    MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build());
                })
                .build();
    }

    private static WebClient statusClient(HttpStatus status, String body) {
        return WebClient.builder()
                .baseUrl("https://provider.test/")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                        .header(
                                HttpHeaders.CONTENT_TYPE,
                                MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build()))
                .build();
    }

    private static NetworkRiskMetrics metrics() {
        return new NetworkRiskMetrics(new SimpleMeterRegistry());
    }

    private static NetworkRiskProperties properties(boolean ipingEnabled) {
        return properties(ipingEnabled, Duration.ofSeconds(8));
    }

    private static NetworkRiskProperties properties(
            boolean ipingEnabled,
            Duration lookupTimeout) {
        String secret = Base64.getEncoder().encodeToString(
                "provider-test-network-risk-secret-012345".getBytes());
        return new NetworkRiskProperties(
                NetworkRiskMode.ENFORCE,
                secret,
                secret,
                URI.create("https://ip2location.test/"),
                URI.create("https://iping.test/v1/query"),
                ipingEnabled,
                lookupTimeout,
                Duration.ofHours(6),
                Duration.ofMinutes(10),
                Duration.ofSeconds(9),
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
                Duration.ofSeconds(15),
                List.of(
                        URI.create("stun:stun.l.google.com:19302"),
                        URI.create("stun:stun.cloudflare.com:3478"),
                        URI.create("stun:global.stun.twilio.com:3478"),
                        URI.create("stun:stun.nextcloud.com:3478")),
                8,
                secret);
    }
}
