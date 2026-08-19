package com.example.temperate.service.user.apikey.bloom.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.model.ai.entity.UserApiKey;
import com.example.temperate.service.bloom.CountingBloomEngine;
import com.example.temperate.service.bloom.CountingBloomEngine.BuildFence;
import com.example.temperate.service.bloom.CountingBloomNamespace;
import com.example.temperate.service.user.apikey.config.ApiKeyProperties;
import com.example.temperate.service.user.apikey.credential.ApiKeyCredentialService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 该组件是来通过可续租 Redis Leader Lease 清空并分页重建固定 v1 API Key Bloom；任何失效或构建错误都保持 DEGRADED 并回源数据库。
 */
@Component
public final class ApiKeyBloomRebuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyBloomRebuilder.class);
    private static final Duration LEASE_TTL = Duration.ofSeconds(45);
    private static final long EXPECTED_MAXIMUM_ELEMENTS = 1_000_000L;
    private static final RedisScript<Long> RENEW_LEADER = renewScript();
    private static final RedisScript<Long> ACQUIRE_LEADER = acquireScript();

    private final ApiKeyProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final CountingBloomEngine engine;
    private final CountingBloomNamespace namespace;
    private final ApiKeyCredentialService credentialService;
    private final UserApiKeyMapper apiKeyMapper;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile BuildFence leaderFence;

    public ApiKeyBloomRebuilder(
            ApiKeyProperties properties,
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            CountingBloomEngine engine,
            @Qualifier("apiKeyCountingBloomNamespace")
            CountingBloomNamespace namespace,
            ApiKeyCredentialService credentialService,
            UserApiKeyMapper apiKeyMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.properties = Objects.requireNonNull(properties);
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.engine = Objects.requireNonNull(engine);
        this.namespace = Objects.requireNonNull(namespace);
        this.credentialService = Objects.requireNonNull(credentialService);
        this.apiKeyMapper = Objects.requireNonNull(apiKeyMapper);
        this.clock = Objects.requireNonNull(clock);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        maintainLeaderAndRebuild();
    }

    @Scheduled(fixedDelay = 15000L)
    public void maintainLeaderAndRebuild() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (leaderFence != null && renew(leaderFence)) {
                Object state = redisTemplate.opsForHash().get(
                        namespace.metaKey(), "state");
                if ("ACTIVE".equals(state)) {
                    return;
                }
                // DEGRADED 或不完整状态持续到下一轮维护时，当前 Leader 必须重建，不能只续租后永久停滞。
                rebuild(leaderFence);
                return;
            }
            leaderFence = null;
            BuildFence acquired = tryAcquire();
            if (acquired == null) {
                return;
            }
            leaderFence = acquired;
            rebuild(acquired);
        } catch (RuntimeException exception) {
            BuildFence failedFence = leaderFence;
            if (failedFence != null) {
                engine.markBuildDegraded(
                        namespace, failedFence, "leader_rebuild_failed");
            }
            LOGGER.warn(
                    "event=api_key_bloom_rebuild_failed traceId=bloom-rebuild cause={}",
                    exception.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    private void rebuild(BuildFence fence) {
        engine.initializeBuilding(namespace, fence);
        byte[] afterId = null;
        long elementCount = 0;
        long databaseCount = 0;
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int pageSize = properties.getBloom().getBuildPageSize();
        while (true) {
            if (!renew(fence)) {
                throw new IllegalStateException("API Key Bloom Leader Lease was lost");
            }
            List<UserApiKey> page = apiKeyMapper.findBloomBuildPage(afterId, now, pageSize);
            if (page.isEmpty()) {
                break;
            }
            if (databaseCount + page.size() > EXPECTED_MAXIMUM_ELEMENTS) {
                throw new IllegalStateException(
                        "API Key Bloom expected element capacity was exceeded");
            }
            databaseCount += page.size();
            long newlyAdded = engine.addBuildBatch(
                    namespace,
                    page.stream()
                            .map(UserApiKey::getKeyDigest)
                            .map(credentialService::digestIdentifier)
                            .toList(),
                    fence);
            // 并发 positive mutation 可能先写入同一摘要；Receipt 返回的实际新增数才与 Redis element_count 一致。
            elementCount += newlyAdded;
            meterRegistry.counter("api.key.bloom.build", "result", "loaded")
                    .increment(newlyAdded);
            afterId = page.get(page.size() - 1).getId();
            if (page.size() < pageSize) {
                break;
            }
        }
        if (!renew(fence)) {
            throw new IllegalStateException(
                    "API Key Bloom Leader Lease was lost before mutation recovery");
        }
        elementCount += engine.recoverPositiveMutations(namespace, fence);
        if (elementCount > EXPECTED_MAXIMUM_ELEMENTS) {
            throw new IllegalStateException(
                    "API Key Bloom expected element capacity was exceeded");
        }
        if (!renew(fence)) {
            throw new IllegalStateException(
                    "API Key Bloom Leader Lease was lost before activation");
        }
        if (!engine.validateAndActivate(
                namespace, elementCount, EXPECTED_MAXIMUM_ELEMENTS, fence)) {
            throw new IllegalStateException("API Key Bloom validation failed");
        }
        LOGGER.info(
                "event=api_key_bloom_rebuild_completed traceId=bloom-rebuild element_count={}",
                elementCount);
        meterRegistry.counter("api.key.bloom.build", "result", "completed").increment();
    }

    private BuildFence tryAcquire() {
        String token = UUID.randomUUID().toString();
        Long epoch = redisTemplate.execute(
                ACQUIRE_LEADER,
                List.of(
                        keyFactory.apiKeyBloomLeaderKey(),
                        keyFactory.apiKeyBloomFenceKey()),
                token,
                Long.toString(LEASE_TTL.toMillis()));
        if (epoch == null || epoch <= 0) {
            return null;
        }
        return new BuildFence(
                keyFactory.apiKeyBloomLeaderKey(),
                epoch + ":" + token,
                epoch,
                LEASE_TTL);
    }

    private boolean renew(BuildFence fence) {
        Long renewed = redisTemplate.execute(
                RENEW_LEADER,
                List.of(fence.leaderKey()),
                fence.leaseValue(),
                Long.toString(LEASE_TTL.toMillis()));
        return renewed != null && renewed == 1L;
    }

    private static RedisScript<Long> acquireScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "redis-scripts/counting-bloom/acquire-leader.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<Long> renewScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(
                "redis-scripts/counting-bloom/renew-leader.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
