package com.example.temperate.service.risk.ipintel.provider.impl;

import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ip2location.domain.AcquiredIp2LocationKey;
import com.example.temperate.service.risk.ip2location.service.Ip2LocationApiKeyService;
import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.domain.ProviderFailureType;
import com.example.temperate.service.risk.ipintel.domain.ProviderIpIntelligenceResult;
import com.example.temperate.service.risk.ipintel.provider.ExternalIpIntelligenceProvider;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 使用加密 Redis Key 池和异步 WebClient 查询 IP2Location 风险与地理信息。
 *
 * <p>本实现只解析评分所需字段，不记录明文 IP、API Key 或原始响应；鉴权和额度错误会淘汰已经明确失效的
 * Key，网络超时则不补偿本地额度，因为无法确认供应商是否已计费。</p>
 */
@Component
public final class Ip2LocationIpIntelligenceProvider
        implements ExternalIpIntelligenceProvider {

    private final Ip2LocationApiKeyService keyService;
    private final WebClient webClient;
    private final NetworkRiskProperties properties;
    private final NetworkRiskMetrics metrics;

    public Ip2LocationIpIntelligenceProvider(
            Ip2LocationApiKeyService keyService,
            @Qualifier("ip2LocationRiskWebClient") WebClient webClient,
            NetworkRiskProperties properties,
            NetworkRiskMetrics metrics) {
        this.keyService = Objects.requireNonNull(keyService);
        this.webClient = Objects.requireNonNull(webClient);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public ExternalIpProviderType type() {
        return ExternalIpProviderType.IP2LOCATION;
    }

    @Override
    public Mono<ProviderIpIntelligenceResult> query(String canonicalClientIp) {
        if (canonicalClientIp == null || canonicalClientIp.isBlank()) {
            return observed(Mono.just(failed(ProviderFailureType.INVALID_INPUT)));
        }
        return observed(Mono.fromCallable(keyService::acquire)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(acquired -> acquired
                        .map(key -> queryWithKey(canonicalClientIp, key))
                        .orElseGet(() -> Mono.just(failed(ProviderFailureType.NO_CREDENTIAL))))
                .timeout(
                        properties.lookupTimeout(),
                        Mono.just(failed(ProviderFailureType.TIMEOUT)))
                .onErrorReturn(failed(ProviderFailureType.UNAVAILABLE)));
    }

    private Mono<ProviderIpIntelligenceResult> queryWithKey(
            String canonicalClientIp,
            AcquiredIp2LocationKey key) {
        return webClient.get()
                .uri(builder -> builder.queryParam("ip", canonicalClientIp).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.apiKey())
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();
                    if (status.is2xxSuccessful()) {
                        return response.bodyToMono(JsonNode.class)
                                .map(this::parse)
                                .defaultIfEmpty(failed(ProviderFailureType.EMPTY_RESPONSE));
                    }
                    ProviderFailureType failure = failureType(status.value());
                    if (failure == ProviderFailureType.AUTHENTICATION
                            || failure == ProviderFailureType.QUOTA_EXHAUSTED) {
                        // 供应商已经明确拒绝凭据时才从池中淘汰，避免瞬时 5xx 错删仍可用 Key。
                        return response.releaseBody()
                                .then(Mono.fromRunnable(
                                                () -> keyService.discard(key.keyId()))
                                        .subscribeOn(Schedulers.boundedElastic()))
                                .thenReturn(failed(failure));
                    }
                    return response.releaseBody().thenReturn(failed(failure));
                });
    }

    private ProviderIpIntelligenceResult parse(JsonNode payload) {
        Integer riskScore = integer(payload, "fraud_score");
        Integer trustScore = riskScore == null ? null : 100 - clamp(riskScore);
        return new ProviderIpIntelligenceResult(
                type(),
                hasUsefulValue(payload, trustScore),
                ProviderFailureType.NONE,
                trustScore,
                country(payload),
                asn(payload),
                decimal(payload, "latitude", -90, 90),
                decimal(payload, "longitude", -180, 180),
                networkType(payload),
                trustScore != null);
    }

    private static boolean hasUsefulValue(JsonNode payload, Integer trustScore) {
        return trustScore != null
                || text(payload, "country_code") != null
                || text(payload, "country") != null
                || decimal(payload, "latitude", -90, 90) != null;
    }

    private static ProviderFailureType failureType(int status) {
        if (status == 401 || status == 403) {
            return ProviderFailureType.AUTHENTICATION;
        }
        if (status == 429) {
            return ProviderFailureType.QUOTA_EXHAUSTED;
        }
        return ProviderFailureType.HTTP_STATUS;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String country(JsonNode payload) {
        String value = firstText(payload, "country_code", "country");
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return normalized.matches("^[A-Z]{2}$") ? normalized : null;
    }

    private static Long asn(JsonNode payload) {
        String value = firstText(payload, "asn");
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT).replaceFirst("^AS", "");
        try {
            long parsed = Long.parseLong(normalized);
            return parsed >= 0 && parsed <= 4_294_967_295L ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static NetworkType networkType(JsonNode payload) {
        JsonNode proxy = payload == null ? null : payload.path("proxy");
        if (booleanValue(proxy, "is_tor")) {
            return NetworkType.TOR;
        }
        if (booleanValue(proxy, "is_vpn")) {
            return NetworkType.VPN;
        }
        if (booleanValue(proxy, "is_public_proxy")) {
            return NetworkType.PUBLIC_PROXY;
        }
        if (booleanValue(proxy, "is_web_proxy")) {
            return NetworkType.WEB_PROXY;
        }
        if (booleanValue(proxy, "is_data_center")) {
            return NetworkType.DATA_CENTER;
        }
        return mapNetworkType(firstText(payload, "usage_type"),
                firstText(payload == null ? null : payload.path("as_info"), "as_usage_type"));
    }

    static NetworkType mapNetworkType(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String token = candidate.trim()
                    .toUpperCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_');
            if (token.contains("RESIDENTIAL") || token.equals("RES")) {
                return NetworkType.RESIDENTIAL;
            }
            if (token.contains("MOBILE") || token.contains("CELLULAR")) {
                return NetworkType.MOBILE;
            }
            if (token.contains("DATA") || token.equals("DCH") || token.equals("IDC")) {
                return NetworkType.DATA_CENTER;
            }
            if (token.contains("BUSINESS") || token.equals("COM") || token.equals("ISP")) {
                return NetworkType.BUSINESS;
            }
        }
        return NetworkType.UNKNOWN;
    }

    private static Integer integer(JsonNode payload, String field) {
        String value = text(payload, field);
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static BigDecimal decimal(JsonNode payload, String field, int minimum, int maximum) {
        String value = text(payload, field);
        try {
            BigDecimal parsed = value == null ? null : new BigDecimal(value);
            return parsed != null
                            && parsed.compareTo(BigDecimal.valueOf(minimum)) >= 0
                            && parsed.compareTo(BigDecimal.valueOf(maximum)) <= 0
                    ? parsed
                    : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static String firstText(JsonNode payload, String... fields) {
        for (String field : fields) {
            String value = text(payload, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static String text(JsonNode payload, String field) {
        if (payload == null || payload.isNull() || field == null) {
            return null;
        }
        JsonNode value = payload.get(field);
        if (value == null || value.isNull() || value.isContainerNode()) {
            return null;
        }
        String normalized = value.asText("").trim();
        return normalized.isEmpty() || "-".equals(normalized) ? null : normalized;
    }

    private static boolean booleanValue(JsonNode payload, String field) {
        String value = text(payload, field);
        return value != null
                && ("true".equalsIgnoreCase(value) || "1".equals(value));
    }

    private static ProviderIpIntelligenceResult failed(ProviderFailureType failureType) {
        return ProviderIpIntelligenceResult.failed(
                ExternalIpProviderType.IP2LOCATION,
                failureType);
    }

    private Mono<ProviderIpIntelligenceResult> observed(
            Mono<ProviderIpIntelligenceResult> result) {
        return result.doOnNext(value -> metrics.provider(
                type(),
                value.failureType(),
                value.success()));
    }
}
