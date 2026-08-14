package com.example.temperate.service.auth.identity.bloom.store.impl;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.identity.bloom.ProtectedIdentityPresenceRecord;
import com.example.temperate.service.auth.identity.bloom.store.IdentityPresenceBloomStore;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.CompositeRecord;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.Field;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.Generation;
import com.example.temperate.service.bloom.VersionedCompositeCountingBloomEngine.Namespace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 该身份领域包装器是来把邮箱、手机号和用户 Receipt 映射到通用双字段计数 Bloom，引擎之外不直接执行 Redis I/O。
 *
 * <p>主字段固定为邮箱，次字段固定为可选手机号；Redis Key 全部在这里通过 RedisKeyFactory 构造，通用引擎只接收
 * 有界且已经校验的命名空间和代次 Key 列表。</p>
 */
@Component
public final class RedisIdentityPresenceBloomStore
        implements IdentityPresenceBloomStore {

    private static final String BLOOM_DOMAIN = "bloom";
    private static final String EMAIL_OBJECT = "uli-email";
    private static final String PHONE_OBJECT = "uli-phone";

    private final VersionedCompositeCountingBloomEngine engine;
    private final RedisKeyFactory keyFactory;
    private final IdentityPresenceBloomSettings settings;
    private final Namespace namespace;

    public RedisIdentityPresenceBloomStore(
            VersionedCompositeCountingBloomEngine engine,
            RedisKeyFactory keyFactory,
            IdentityPresenceBloomSettings settings) {
        this.engine = Objects.requireNonNull(engine);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.settings = Objects.requireNonNull(settings);
        this.namespace = new Namespace(
                new CountingBloomLayout(
                        settings.capacity(),
                        settings.hashCount(),
                        settings.counterBytes(),
                        settings.countersPerBucket()),
                settings.buildBatchSize(),
                settings.receiptShards(),
                settings.maximumElements(),
                keyFactory.identityPresenceBloomControlKey(),
                keyFactory.identityPresenceBloomBuildLockKey());
    }

    @Override
    public IdentityPresenceDecision check(
            IdentityPresenceKind kind,
            HmacIdentifier protectedIdentifier) {
        VersionedCompositeCountingBloomEngine.LookupResult result = engine.lookup(
                namespace,
                Objects.requireNonNull(kind) == IdentityPresenceKind.EMAIL
                        ? Field.PRIMARY
                        : Field.SECONDARY,
                Objects.requireNonNull(protectedIdentifier).value());
        return switch (result) {
            case DEFINITELY_NOT_PRESENT -> IdentityPresenceDecision.DEFINITELY_ABSENT;
            case MAYBE_PRESENT -> IdentityPresenceDecision.POSSIBLY_PRESENT;
            case UNAVAILABLE -> IdentityPresenceDecision.UNAVAILABLE;
        };
    }

    @Override
    public IdentityPresenceMutationResult add(
            ProtectedIdentityPresenceRecord record) {
        return addAll(List.of(Objects.requireNonNull(record)));
    }

    @Override
    public IdentityPresenceMutationResult addAll(
            List<ProtectedIdentityPresenceRecord> records) {
        return mapMutation(engine.addAll(namespace, mapRecords(records)));
    }

    @Override
    public IdentityPresenceMutationResult remove(
            ProtectedIdentityPresenceRecord record) {
        return removeAll(List.of(Objects.requireNonNull(record)));
    }

    @Override
    public IdentityPresenceMutationResult removeAll(
            List<ProtectedIdentityPresenceRecord> records) {
        return mapMutation(engine.removeAll(namespace, mapRecords(records)));
    }

    @Override
    public boolean tryAcquireBuildLease(String leaseToken, Duration ttl) {
        return engine.tryAcquireBuildLease(namespace, leaseToken, ttl);
    }

    @Override
    public boolean renewBuildLease(String leaseToken, Duration ttl) {
        return engine.renewBuildLease(namespace, leaseToken, ttl);
    }

    @Override
    public String beginBuild(String generation) {
        return engine.beginBuild(namespace, generation(generation));
    }

    @Override
    public void markReady(String generation) {
        engine.markReady(namespace, generation);
    }

    @Override
    public void activate(String generation) {
        engine.activate(namespace, generation);
    }

    @Override
    public void cleanupGeneration(String generation) {
        if (generation == null || generation.isBlank()) {
            return;
        }
        engine.cleanupGeneration(generation(generation));
    }

    @Override
    public void markDegraded(String reason) {
        engine.markDegraded(namespace, reason);
    }

    @Override
    public void releaseBuildLease(String leaseToken) {
        engine.releaseBuildLease(namespace, leaseToken);
    }

    private List<CompositeRecord> mapRecords(
            List<ProtectedIdentityPresenceRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<CompositeRecord> mapped = new ArrayList<>(records.size());
        for (ProtectedIdentityPresenceRecord record : records) {
            ProtectedIdentityPresenceRecord required = Objects.requireNonNull(record);
            mapped.add(new CompositeRecord(
                    Long.toString(required.userId()),
                    receiptShard(required.userId()),
                    required.protectedEmail().value(),
                    required.protectedPhone() == null
                            ? null
                            : required.protectedPhone().value()));
        }
        return List.copyOf(mapped);
    }

    private Generation generation(String generation) {
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException(
                    "Identity Bloom generation must not be blank");
        }
        List<String> emailBuckets = new ArrayList<>(namespace.layout().bucketCount());
        List<String> phoneBuckets = new ArrayList<>(namespace.layout().bucketCount());
        for (int bucket = 0; bucket < namespace.layout().bucketCount(); bucket++) {
            emailBuckets.add(keyFactory.bucketKey(
                    BLOOM_DOMAIN, EMAIL_OBJECT, generation, bucket));
            phoneBuckets.add(keyFactory.bucketKey(
                    BLOOM_DOMAIN, PHONE_OBJECT, generation, bucket));
        }
        List<String> receiptKeys = new ArrayList<>(settings.receiptShards());
        for (int shard = 0; shard < settings.receiptShards(); shard++) {
            receiptKeys.add(keyFactory.identityPresenceBloomReceiptKey(
                    generation, shard));
        }
        return new Generation(
                generation,
                keyFactory.identityPresenceBloomMetaKey(generation),
                emailBuckets,
                phoneBuckets,
                receiptKeys);
    }

    private int receiptShard(long userId) {
        return Long.hashCode(userId) & (settings.receiptShards() - 1);
    }

    private static IdentityPresenceMutationResult mapMutation(
            VersionedCompositeCountingBloomEngine.MutationResult result) {
        return switch (result) {
            case UPDATED -> IdentityPresenceMutationResult.APPLIED;
            case ALREADY_APPLIED -> IdentityPresenceMutationResult.ALREADY_APPLIED;
            case OVERFLOW -> IdentityPresenceMutationResult.OVERFLOW;
            case UNDERFLOW -> IdentityPresenceMutationResult.UNDERFLOW;
            case CAPACITY_EXCEEDED -> IdentityPresenceMutationResult.CAPACITY_EXCEEDED;
            case UNAVAILABLE -> IdentityPresenceMutationResult.UNAVAILABLE;
        };
    }
}
