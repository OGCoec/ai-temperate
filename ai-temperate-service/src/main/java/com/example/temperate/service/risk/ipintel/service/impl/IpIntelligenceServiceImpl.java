package com.example.temperate.service.risk.ipintel.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.ipintel.cache.IpIntelligenceCache;
import com.example.temperate.service.risk.ipintel.domain.ExternalIpProviderType;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceLookupResult;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSource;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.ipintel.domain.ProviderIpIntelligenceResult;
import com.example.temperate.service.risk.ipintel.local.LocalIpGeoProvider;
import com.example.temperate.service.risk.ipintel.local.LocalIpGeoResult;
import com.example.temperate.service.risk.ipintel.provider.ExternalIpIntelligenceProviderRegistry;
import com.example.temperate.service.risk.ipintel.service.IpIntelligenceService;
import com.example.temperate.service.risk.observability.NetworkRiskMetrics;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 编排 Redis 缓存、IP2Location、iPing 和本地 BIN 的有界 IP 情报降级链。
 *
 * <p>整个链路共享一个绝对八秒上限；只有拿到 single-flight 锁和本机并发许可的请求调用外部供应商，
 * 其他请求短暂等待缓存，超时后立即使用本地数据与默认 0 分，以触发严格拦截。</p>
 */
@Service
public final class IpIntelligenceServiceImpl implements IpIntelligenceService {

    private static final int DEFAULT_TRUST_SCORE = 0;

    private final NetworkRiskIdentifier identifier;
    private final IpIntelligenceCache cache;
    private final ExternalIpIntelligenceProviderRegistry providers;
    private final LocalIpGeoProvider localGeoProvider;
    private final NetworkRiskProperties properties;
    private final Semaphore bulkhead;
    private final NetworkRiskMetrics metrics;

    public IpIntelligenceServiceImpl(
            NetworkRiskIdentifier identifier,
            IpIntelligenceCache cache,
            ExternalIpIntelligenceProviderRegistry providers,
            LocalIpGeoProvider localGeoProvider,
            NetworkRiskProperties properties,
            @Qualifier("networkRiskLookupBulkhead") Semaphore bulkhead,
            NetworkRiskMetrics metrics) {
        this.identifier = Objects.requireNonNull(identifier);
        this.cache = Objects.requireNonNull(cache);
        this.providers = Objects.requireNonNull(providers);
        this.localGeoProvider = Objects.requireNonNull(localGeoProvider);
        this.properties = Objects.requireNonNull(properties);
        this.bulkhead = Objects.requireNonNull(bulkhead);
        this.metrics = Objects.requireNonNull(metrics);
    }

    @Override
    public Mono<IpIntelligenceLookupResult> lookup(String canonicalClientIp) {
        String canonicalIp = identifier.canonicalIp(canonicalClientIp);
        HmacIdentifier ipDigest = identifier.identifyIp(canonicalIp);
        Mono<IpIntelligenceSnapshot> fallback = localFallback(canonicalIp)
                .flatMap(snapshot -> cacheBestEffort(ipDigest, snapshot));
        Duration externalBudget = externalLookupBudget(properties.lookupTimeout());
        return Mono.defer(() -> cachedBestEffort(ipDigest)
                        .doOnNext(ignored -> metrics.ipIntelligenceCache("hit"))
                        .map(snapshot -> new IpIntelligenceLookupResult(
                                snapshot,
                                true))
                        .switchIfEmpty(Mono.defer(() -> {
                            metrics.ipIntelligenceCache("miss");
                            return resolveMiss(canonicalIp, ipDigest)
                                    .map(snapshot ->
                                            new IpIntelligenceLookupResult(
                                                    snapshot,
                                                    false));
                        })))
                /*
                 * 外部链比 MVC 总上限略早截止，为本地 BIN/default 结果保留确定性完成窗口；
                 * 否则两个相同截止点竞态时，外层可能在降级信号发出前先抛出阻塞超时。
                 */
                .timeout(externalBudget, Mono.defer(() -> {
                    metrics.ipIntelligenceLookup("timeout_fallback");
                    return fallback.map(snapshot ->
                            new IpIntelligenceLookupResult(snapshot, false));
                }))
                .onErrorResume(ignored -> {
                    metrics.ipIntelligenceLookup("error_fallback");
                    return fallback.map(snapshot ->
                            new IpIntelligenceLookupResult(snapshot, false));
                });
    }

    private Mono<IpIntelligenceSnapshot> resolveMiss(
            String canonicalIp,
            HmacIdentifier ipDigest) {
        if (!bulkhead.tryAcquire()) {
            metrics.ipIntelligenceLookup("bulkhead_fallback");
            return localFallback(canonicalIp)
                    .flatMap(snapshot -> cacheBestEffort(ipDigest, snapshot));
        }
        String owner = UUID.randomUUID().toString();
        return Mono.fromCallable(() -> cache.tryAcquireLookup(
                        ipDigest,
                        owner,
                        properties.singleFlightTtl()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(acquired -> new SingleFlightAcquisition(acquired, true))
                /*
                 * Redis 是性能协调层而不是权威风险来源。协调服务故障时仍受本机舱壁保护地查询供应商，
                 * 否则 Redis 短暂不可用会把所有新 IP 错误地固定降级为不可用。
                 */
                .onErrorResume(ignored -> {
                    metrics.ipIntelligenceLookup("single_flight_acquire_error");
                    metrics.ipIntelligenceLookup("coordination_bypass");
                    return Mono.just(new SingleFlightAcquisition(false, false));
                })
                .flatMap(acquisition -> {
                    if (!acquisition.coordinationAvailable()) {
                        return queryAndCache(canonicalIp, ipDigest);
                    }
                    metrics.ipIntelligenceLookup(
                            acquisition.ownerAcquired()
                                    ? "single_flight_owner"
                                    : "single_flight_wait");
                    if (!acquisition.ownerAcquired()) {
                        return waitForOwner(canonicalIp, ipDigest);
                    }
                    return queryAndCache(canonicalIp, ipDigest)
                            .doFinally(signal -> releaseLookupBestEffort(ipDigest, owner));
                })
                .doFinally(signal -> bulkhead.release());
    }

    private Mono<IpIntelligenceSnapshot> waitForOwner(
            String canonicalIp,
            HmacIdentifier ipDigest) {
        Duration pollingWindow = properties.lookupTimeout()
                .minus(Duration.ofMillis(100));
        return Flux.interval(Duration.ZERO, Duration.ofMillis(100))
                .take(pollingWindow)
                .concatMap(ignored -> cachedBestEffort(ipDigest))
                .next()
                .switchIfEmpty(localFallback(canonicalIp)
                        .flatMap(snapshot -> cacheBestEffort(
                                ipDigest,
                                snapshot)));
    }

    private Mono<IpIntelligenceSnapshot> queryAndCache(
            String canonicalIp,
            HmacIdentifier ipDigest) {
        return providers.getRequired(ExternalIpProviderType.IP2LOCATION)
                .query(canonicalIp)
                .flatMap(ip2 -> ip2.hasTrustScore()
                        ? Mono.just(merge(ip2, null, local(canonicalIp)))
                        : providers.getRequired(ExternalIpProviderType.IPING)
                                .query(canonicalIp)
                                .map(iping -> merge(ip2, iping, local(canonicalIp))))
                .switchIfEmpty(localFallback(canonicalIp))
                .doOnNext(snapshot -> metrics.ipIntelligenceLookup(
                        snapshot.source().name().toLowerCase(java.util.Locale.ROOT)))
                .flatMap(snapshot -> cacheBestEffort(ipDigest, snapshot));
    }

    private Mono<IpIntelligenceSnapshot> cached(HmacIdentifier ipDigest) {
        return Mono.fromCallable(() -> cache.find(ipDigest))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optional -> optional.map(Mono::just).orElseGet(Mono::empty));
    }

    private Mono<IpIntelligenceSnapshot> cachedBestEffort(HmacIdentifier ipDigest) {
        return cached(ipDigest).onErrorResume(ignored -> {
            metrics.ipIntelligenceLookup("cache_read_error");
            return Mono.empty();
        });
    }

    private void releaseLookupBestEffort(HmacIdentifier ipDigest, String owner) {
        Mono.fromRunnable(() -> cache.releaseLookup(ipDigest, owner))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ignored -> {
                    metrics.ipIntelligenceLookup("single_flight_release_error");
                    return Mono.empty();
                })
                .subscribe();
    }

    private Mono<IpIntelligenceSnapshot> cacheBestEffort(
            HmacIdentifier ipDigest,
            IpIntelligenceSnapshot snapshot) {
        Duration baseTtl = snapshot.source() == IpIntelligenceSource.LOCAL_BIN
                        || snapshot.source() == IpIntelligenceSource.DEFAULT
                ? properties.fallbackCacheTtl()
                : properties.positiveCacheTtl();
        Duration jittered = jitter(baseTtl);
        return Mono.fromRunnable(() -> cache.store(ipDigest, snapshot, jittered))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(snapshot)
                .onErrorResume(ignored -> {
                    // Redis 写入失败不能把已经取得的风险结果降级或中断；后续请求会自然再次查询。
                    metrics.ipIntelligenceLookup("cache_store_error");
                    return Mono.just(snapshot);
                });
    }

    private Mono<IpIntelligenceSnapshot> localFallback(String canonicalIp) {
        return Mono.fromCallable(() -> {
                    Optional<LocalIpGeoResult> local = local(canonicalIp);
                    return snapshot(
                            DEFAULT_TRUST_SCORE,
                            local.map(LocalIpGeoResult::countryCode).orElse(null),
                            local.map(LocalIpGeoResult::asn).orElse(null),
                            local.map(LocalIpGeoResult::latitude).orElse(null),
                            local.map(LocalIpGeoResult::longitude).orElse(null),
                            NetworkType.UNKNOWN,
                            false,
                            local.isPresent()
                                    ? IpIntelligenceSource.LOCAL_BIN
                                    : IpIntelligenceSource.DEFAULT);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private IpIntelligenceSnapshot merge(
            ProviderIpIntelligenceResult ip2,
            ProviderIpIntelligenceResult iping,
            Optional<LocalIpGeoResult> local) {
        ProviderIpIntelligenceResult scoreSource =
                ip2 != null && ip2.hasTrustScore() ? ip2 : iping;
        int trustScore = scoreSource != null && scoreSource.trustScore() != null
                ? scoreSource.trustScore()
                : DEFAULT_TRUST_SCORE;
        String country = first(
                ip2 == null ? null : ip2.countryCode(),
                iping == null ? null : iping.countryCode(),
                local.map(LocalIpGeoResult::countryCode).orElse(null));
        Long asn = first(
                ip2 == null ? null : ip2.asn(),
                iping == null ? null : iping.asn(),
                local.map(LocalIpGeoResult::asn).orElse(null));
        BigDecimal latitude = first(
                ip2 == null ? null : ip2.latitude(),
                iping == null ? null : iping.latitude(),
                local.map(LocalIpGeoResult::latitude).orElse(null));
        BigDecimal longitude = first(
                ip2 == null ? null : ip2.longitude(),
                iping == null ? null : iping.longitude(),
                local.map(LocalIpGeoResult::longitude).orElse(null));
        NetworkType networkType = firstNetwork(ip2, iping);
        IpIntelligenceSource source = source(ip2, scoreSource, local);
        return snapshot(
                trustScore,
                country,
                asn,
                latitude,
                longitude,
                networkType,
                scoreSource != null && scoreSource.scoreIncludesNetworkRisk(),
                source);
    }

    private Optional<LocalIpGeoResult> local(String canonicalIp) {
        return localGeoProvider.findGeo(canonicalIp);
    }

    private static IpIntelligenceSource source(
            ProviderIpIntelligenceResult ip2,
            ProviderIpIntelligenceResult scoreSource,
            Optional<LocalIpGeoResult> local) {
        if (scoreSource != null
                && scoreSource.hasTrustScore()
                && scoreSource.provider() == ExternalIpProviderType.IP2LOCATION) {
            return IpIntelligenceSource.IP2LOCATION;
        }
        if (scoreSource != null
                && scoreSource.hasTrustScore()
                && ip2 != null
                && ip2.success()) {
            return IpIntelligenceSource.IP2LOCATION_AND_IPING;
        }
        if (scoreSource != null && scoreSource.hasTrustScore()) {
            return IpIntelligenceSource.IPING;
        }
        // 没有任何供应商风险分时，0 分会触发 BLOCK；仍使用短 TTL，避免把供应商暂时不可用永久缓存。
        return local.isPresent() ? IpIntelligenceSource.LOCAL_BIN : IpIntelligenceSource.DEFAULT;
    }

    private static NetworkType firstNetwork(
            ProviderIpIntelligenceResult first,
            ProviderIpIntelligenceResult second) {
        if (first != null && first.networkType() != NetworkType.UNKNOWN) {
            return first.networkType();
        }
        if (second != null && second.networkType() != NetworkType.UNKNOWN) {
            return second.networkType();
        }
        return NetworkType.UNKNOWN;
    }

    private static IpIntelligenceSnapshot snapshot(
            int trustScore,
            String country,
            Long asn,
            BigDecimal latitude,
            BigDecimal longitude,
            NetworkType networkType,
            boolean scoreIncludesNetworkRisk,
            IpIntelligenceSource source) {
        return new IpIntelligenceSnapshot(
                IpIntelligenceSnapshot.CURRENT_SCHEMA_VERSION,
                trustScore,
                country,
                asn,
                latitude,
                longitude,
                networkType,
                scoreIncludesNetworkRisk,
                source);
    }

    private static Duration jitter(Duration base) {
        double factor = 0.8D + Math.random() * 0.4D;
        return Duration.ofMillis(Math.max(1, Math.round(base.toMillis() * factor)));
    }

    private static Duration externalLookupBudget(Duration totalBudget) {
        long totalMillis = totalBudget.toMillis();
        long fallbackReserveMillis = Math.min(
                250L,
                Math.max(1L, totalMillis / 10L));
        return Duration.ofMillis(Math.max(1L, totalMillis - fallbackReserveMillis));
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 绑定 single-flight 获取结果与协调层可用性，避免 Redis 故障降级路径错误释放其他实例的锁。
     */
    private record SingleFlightAcquisition(
            boolean ownerAcquired,
            boolean coordinationAvailable) {
    }
}
