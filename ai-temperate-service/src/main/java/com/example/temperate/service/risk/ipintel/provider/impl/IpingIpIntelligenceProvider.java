package com.example.temperate.service.risk.ipintel.provider.impl;

import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.domain.ProviderFailureType;
import com.example.temperate.service.risk.ipintel.domain.ProviderIpIntelligenceResult;
import com.example.temperate.service.risk.ipintel.provider.ExternalIpIntelligenceProvider;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 使用异步 WebClient 查询 iPing，作为 IP2Location 缺少风险分或失败后的 IPv4 降级实现。
 *
 * <p>iPing 当前按 IPv4 能力建模；IPv6 会明确返回不可用并继续本地降级，绝不把无法查询误认为低风险。</p>
 */
@Component
public final class IpingIpIntelligenceProvider
        implements ExternalIpIntelligenceProvider {

    private final WebClient webClient;
    private final NetworkRiskProperties properties;
    private final NetworkRiskMetrics metrics;

    public IpingIpIntelligenceProvider(
            @Qualifier("ipingRiskWebClient") WebClient webClient,
            NetworkRiskProperties properties,
            NetworkRiskMetrics metrics) {
        this.webClient = Objects.requireNonNull(webClient);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public ExternalIpProviderType type() {
        return ExternalIpProviderType.IPING;
    }

    @Override
    public Mono<ProviderIpIntelligenceResult> query(String canonicalClientIp) {
        if (!properties.ipingEnabled()) {
            return observed(Mono.just(failed(ProviderFailureType.UNAVAILABLE)));
        }
        if (canonicalClientIp == null
                || canonicalClientIp.isBlank()
                || canonicalClientIp.contains(":")) {
            return observed(Mono.just(failed(ProviderFailureType.INVALID_INPUT)));
        }
        return observed(webClient.get()
                .uri(builder -> builder
                        .queryParam("ip", canonicalClientIp)
                        .queryParam("language", "en")
                        .build())
                .exchangeToMono(response -> parseResponse(response.statusCode(),
                        response.bodyToMono(JsonNode.class)))
                .timeout(
                        properties.lookupTimeout(),
                        Mono.just(failed(ProviderFailureType.TIMEOUT)))
                .onErrorReturn(failed(ProviderFailureType.UNAVAILABLE)));
    }

    private Mono<ProviderIpIntelligenceResult> parseResponse(
            HttpStatusCode status,
            Mono<JsonNode> body) {
        if (!status.is2xxSuccessful()) {
            return body.thenReturn(failed(
                    status.value() == 429
                            ? ProviderFailureType.QUOTA_EXHAUSTED
                            : ProviderFailureType.HTTP_STATUS));
        }
        return body.map(root -> {
                    Integer businessCode = integer(root, "code");
                    if (businessCode != null && businessCode != 200) {
                        return failed(ProviderFailureType.BUSINESS_RESPONSE);
                    }
                    JsonNode payload = root.hasNonNull("data") ? root.path("data") : root;
                    Integer riskScore = integer(payload, "risk_score");
                    Integer trustScore = riskScore == null
                            ? null
                            : 100 - Math.max(0, Math.min(100, riskScore));
                    return new ProviderIpIntelligenceResult(
                            type(),
                            trustScore != null
                                    || Ip2LocationIpIntelligenceProvider.text(
                                            payload, "country_code") != null,
                            ProviderFailureType.NONE,
                            trustScore,
                            country(payload),
                            asn(payload),
                            Ip2LocationIpIntelligenceProvider.decimal(
                                    payload, "latitude", -90, 90),
                            Ip2LocationIpIntelligenceProvider.decimal(
                                    payload, "longitude", -180, 180),
                            Ip2LocationIpIntelligenceProvider.mapNetworkType(
                                    Ip2LocationIpIntelligenceProvider.text(
                                            payload, "usage_type"),
                                    Ip2LocationIpIntelligenceProvider.text(
                                            payload, "as_type")),
                            trustScore != null);
                })
                .defaultIfEmpty(failed(ProviderFailureType.EMPTY_RESPONSE));
    }

    private static Integer integer(JsonNode payload, String field) {
        String value = Ip2LocationIpIntelligenceProvider.text(payload, field);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String country(JsonNode payload) {
        String value = Ip2LocationIpIntelligenceProvider.text(payload, "country_code");
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.matches("^[A-Z]{2}$") ? normalized : null;
    }

    private static Long asn(JsonNode payload) {
        String value = Ip2LocationIpIntelligenceProvider.text(payload, "asn");
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(
                    value.toUpperCase(Locale.ROOT).replaceFirst("^AS", ""));
            return parsed >= 0 && parsed <= 4_294_967_295L ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static ProviderIpIntelligenceResult failed(ProviderFailureType type) {
        return ProviderIpIntelligenceResult.failed(ExternalIpProviderType.IPING, type);
    }

    private Mono<ProviderIpIntelligenceResult> observed(
            Mono<ProviderIpIntelligenceResult> result) {
        return result.doOnNext(value -> metrics.provider(
                type(),
                value.failureType(),
                value.success()));
    }
}
