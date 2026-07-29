package com.example.temperate.service.auth.identity.bloom.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.common.validation.email.EmailAddressNormalizer;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomObserver;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceDecision;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceKind;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceMutationResult;
import com.example.temperate.service.auth.identity.bloom.ProtectedIdentityPresenceRecord;
import com.example.temperate.service.auth.identity.bloom.store.IdentityPresenceBloomStore;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * 实现身份联系方式的 HMAC 保护、三态查询、提交后幂等更新和后台全量重建。
 *
 * <p>Redis 的任何异常都会转换为 UNAVAILABLE 并触发有限次数重建；数据库事务已提交后的 Bloom 更新
 * 失败不会反向伪装成数据库回滚。</p>
 */
@Service
public final class IdentityPresenceFilterImpl implements IdentityPresenceFilter {

    private static final Duration BUILD_LEASE_TTL = Duration.ofHours(1);
    private static final int MAX_REBUILD_ATTEMPTS = 3;
    private static final String EMAIL_HMAC_PURPOSE = "identity-presence:email:v1";
    private static final String PHONE_HMAC_PURPOSE = "identity-presence:phone:v1";
    private static final System.Logger LOGGER =
            System.getLogger(IdentityPresenceFilterImpl.class.getName());

    private final UserLoginIdentityMapper identityMapper;
    private final IdentityPresenceBloomStore store;
    private final HmacSha256Identifier hmac;
    private final IdentityPresenceBloomSettings settings;
    private final ScheduledExecutorService executor;
    private final IdentityPresenceBloomObserver observer;
    private final Clock clock;
    private final AtomicBoolean rebuildScheduled = new AtomicBoolean();
    private final AtomicBoolean rebuildRunning = new AtomicBoolean();
    private final AtomicInteger rebuildAttempts = new AtomicInteger();

    public IdentityPresenceFilterImpl(
            UserLoginIdentityMapper identityMapper,
            IdentityPresenceBloomStore store,
            HmacSha256Identifier hmac,
            IdentityPresenceBloomSettings settings,
            ScheduledExecutorService executor,
            IdentityPresenceBloomObserver observer,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.store = Objects.requireNonNull(store);
        this.hmac = Objects.requireNonNull(hmac);
        this.settings = Objects.requireNonNull(settings);
        this.executor = Objects.requireNonNull(executor);
        this.observer = Objects.requireNonNull(observer);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public IdentityPresenceDecision checkEmail(String normalizedEmail) {
        requireNormalizedEmail(normalizedEmail);
        return check(
                IdentityPresenceKind.EMAIL,
                protect(EMAIL_HMAC_PURPOSE, normalizedEmail));
    }

    @Override
    public IdentityPresenceDecision checkPhone(String normalizedE164Phone) {
        requireE164Phone(normalizedE164Phone);
        return check(
                IdentityPresenceKind.PHONE,
                protect(PHONE_HMAC_PURPOSE, normalizedE164Phone));
    }

    @Override
    public IdentityPresenceMutationResult recordRegistration(
            long userId,
            String normalizedEmail,
            String normalizedE164Phone) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Registered user ID must be positive.");
        }
        requireNormalizedEmail(normalizedEmail);
        requireE164Phone(normalizedE164Phone);
        if (!settings.enabled()) {
            return IdentityPresenceMutationResult.UNAVAILABLE;
        }
        ProtectedIdentityPresenceRecord record = new ProtectedIdentityPresenceRecord(
                userId,
                protect(EMAIL_HMAC_PURPOSE, normalizedEmail),
                protect(PHONE_HMAC_PURPOSE, normalizedE164Phone));
        try {
            IdentityPresenceMutationResult result = Objects.requireNonNull(
                    store.add(record),
                    "Identity Bloom store mutation result must not be null.");
            observer.mutation(result);
            handleMutationFailure(result);
            return result;
        } catch (RuntimeException exception) {
            degradeAfterFailure("UPDATE_FAILED", exception);
            observer.mutation(IdentityPresenceMutationResult.UNAVAILABLE);
            return IdentityPresenceMutationResult.UNAVAILABLE;
        }
    }

    @Override
    public void recordDatabaseVerification(
            IdentityPresenceKind kind,
            IdentityPresenceDecision decision,
            boolean databaseFound) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(decision);
        if (decision == IdentityPresenceDecision.POSSIBLY_PRESENT && !databaseFound) {
            observer.falsePositive(kind);
        }
    }

    @Override
    public void initializeInBackground() {
        if (settings.enabled()) {
            scheduleRebuild(0L);
        }
    }

    private IdentityPresenceDecision check(
            IdentityPresenceKind kind, HmacIdentifier protectedIdentifier) {
        if (!settings.enabled()) {
            return IdentityPresenceDecision.UNAVAILABLE;
        }
        try {
            IdentityPresenceDecision decision = Objects.requireNonNull(
                    store.check(kind, protectedIdentifier),
                    "Identity Bloom store query result must not be null.");
            observer.query(kind, decision);
            if (decision == IdentityPresenceDecision.UNAVAILABLE) {
                // 构建锁持有者若中途退出，后续请求会以单飞方式重新安排租约竞争，避免永久停留在 BUILDING。
                scheduleRebuild(60L);
            }
            return decision;
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=identity_presence_bloom_query_failed kind=" + kind.name(),
                    exception);
            safeMarkDegraded("QUERY_FAILED");
            observer.query(kind, IdentityPresenceDecision.UNAVAILABLE);
            scheduleRebuild(60L);
            return IdentityPresenceDecision.UNAVAILABLE;
        }
    }

    private void handleMutationFailure(IdentityPresenceMutationResult result) {
        if (result == IdentityPresenceMutationResult.CAPACITY_EXCEEDED) {
            // 同容量重建无法降低误判率，因此只维持降级并告警，等待扩容配置后再由启动或运维触发重建。
            observeDegraded("CAPACITY_EXCEEDED");
            return;
        }
        if (result == IdentityPresenceMutationResult.OVERFLOW
                || result == IdentityPresenceMutationResult.UNDERFLOW
                || result == IdentityPresenceMutationResult.UNAVAILABLE) {
            String reason = switch (result) {
                case OVERFLOW -> "COUNTER_OVERFLOW";
                case UNDERFLOW -> "COUNTER_UNDERFLOW";
                default -> "UPDATE_UNAVAILABLE";
            };
            safeMarkDegraded(reason);
            scheduleRebuild(60L);
        }
    }

    private void scheduleRebuild(long delaySeconds) {
        if (!settings.enabled()
                || rebuildRunning.get()
                || !rebuildScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(() -> {
                if (!rebuildRunning.compareAndSet(false, true)) {
                    rebuildScheduled.set(false);
                    return;
                }
                // 先声明运行态再释放排队态，避免两者同时为 false 的瞬间让并发请求插入重复全量任务。
                rebuildScheduled.set(false);
                long retryDelaySeconds;
                try {
                    retryDelaySeconds = rebuild();
                } finally {
                    rebuildRunning.set(false);
                }
                if (retryDelaySeconds >= 0L) {
                    scheduleRebuild(retryDelaySeconds);
                }
            }, delaySeconds, TimeUnit.SECONDS);
        } catch (RuntimeException schedulingFailure) {
            rebuildScheduled.set(false);
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "event=identity_presence_bloom_build_schedule_failed",
                    schedulingFailure);
        }
    }

    private long rebuild() {
        int attempt = rebuildAttempts.incrementAndGet();
        String leaseToken = UUID.randomUUID().toString();
        long startedNanos = System.nanoTime();
        long processed = 0L;
        boolean leaseAcquired = false;
        String generation = "v1-g"
                + clock.millis()
                + "-"
                + leaseToken.substring(0, 8).toLowerCase(Locale.ROOT);
        try {
            if (!store.tryAcquireBuildLease(leaseToken, BUILD_LEASE_TTL)) {
                rebuildAttempts.set(0);
                return -1L;
            }
            leaseAcquired = true;
            observer.buildStarted();
            String previousGeneration = store.beginBuild(generation);
            long lastId = 0L;
            while (true) {
                List<UserLoginIdentity> page = identityMapper.findIdentityContactsAfterId(
                        lastId, settings.buildBatchSize());
                if (page == null) {
                    throw new IllegalStateException(
                            "Identity Bloom database page must not be null.");
                }
                if (page.isEmpty()) {
                    break;
                }
                List<ProtectedIdentityPresenceRecord> protectedPage =
                        protectPage(page);
                IdentityPresenceMutationResult result = Objects.requireNonNull(
                        store.addAll(protectedPage),
                        "Identity Bloom build mutation result must not be null.");
                if (result == IdentityPresenceMutationResult.OVERFLOW
                        || result == IdentityPresenceMutationResult.CAPACITY_EXCEEDED
                        || result == IdentityPresenceMutationResult.UNAVAILABLE) {
                    throw new BloomBuildException(result);
                }
                processed += page.size();
                observer.buildProgress(processed);
                lastId = requiredLastId(page);
                if (!store.renewBuildLease(leaseToken, BUILD_LEASE_TTL)) {
                    throw new IllegalStateException("Identity Bloom build lease was lost.");
                }
            }
            store.markReady(generation);
            observer.buildReady();
            store.activate(generation);
            if (previousGeneration != null && !previousGeneration.isBlank()) {
                try {
                    // 旧代次清理失败只会暂时占用内存，不影响新 ACTIVE 代次的查询正确性，禁止反向降级新版本。
                    store.cleanupGeneration(previousGeneration);
                } catch (RuntimeException cleanupFailure) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "event=identity_presence_bloom_previous_generation_cleanup_failed",
                            cleanupFailure);
                }
            }
            rebuildAttempts.set(0);
            observer.buildCompleted(processed, System.nanoTime() - startedNanos);
            return -1L;
        } catch (RuntimeException exception) {
            String reason = exception instanceof BloomBuildException buildException
                    ? buildFailureReason(buildException.result)
                    : "BUILD_FAILED";
            safeMarkDegraded(reason);
            observer.buildFailed(reason);
            LOGGER.log(
                    System.Logger.Level.ERROR,
                    "event=identity_presence_bloom_build_failed reason="
                            + reason
                            + " attempt="
                            + attempt,
                    exception);
            if (attempt < MAX_REBUILD_ATTEMPTS
                    && !(exception instanceof BloomBuildException buildException
                    && buildException.result
                    == IdentityPresenceMutationResult.CAPACITY_EXCEEDED)) {
                return attempt == 1 ? 60L : 300L;
            }
            return -1L;
        } finally {
            if (leaseAcquired) {
                try {
                    store.releaseBuildLease(leaseToken);
                } catch (RuntimeException exception) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "event=identity_presence_bloom_build_lease_release_failed",
                            exception);
                }
            }
        }
    }

    private List<ProtectedIdentityPresenceRecord> protectPage(
            List<UserLoginIdentity> page) {
        List<ProtectedIdentityPresenceRecord> protectedPage =
                new ArrayList<>(page.size());
        for (UserLoginIdentity identity : page) {
            if (identity.getId() == null || identity.getEmail() == null) {
                throw new IllegalStateException(
                        "Identity Bloom build row lacks required identity columns.");
            }
            // 全量构建与在线查询必须复用同一邮箱规范化规则，否则同一地址可能落到不同计数器并产生假阴性。
            String email = EmailAddressNormalizer.normalize(identity.getEmail());
            requireNormalizedEmail(email);
            HmacIdentifier phone = null;
            if (identity.getPhone() != null) {
                requireE164Phone(identity.getPhone());
                phone = protect(PHONE_HMAC_PURPOSE, identity.getPhone());
            }
            protectedPage.add(new ProtectedIdentityPresenceRecord(
                    identity.getId(),
                    protect(EMAIL_HMAC_PURPOSE, email),
                    phone));
        }
        return List.copyOf(protectedPage);
    }

    private static long requiredLastId(List<UserLoginIdentity> page) {
        Long id = page.get(page.size() - 1).getId();
        if (id == null || id <= 0) {
            throw new IllegalStateException("Identity Bloom page contains an invalid ID.");
        }
        return id;
    }

    private static String buildFailureReason(IdentityPresenceMutationResult result) {
        return switch (result) {
            case OVERFLOW -> "COUNTER_OVERFLOW";
            case UNDERFLOW -> "COUNTER_UNDERFLOW";
            case CAPACITY_EXCEEDED -> "CAPACITY_EXCEEDED";
            default -> "BUILD_UPDATE_UNAVAILABLE";
        };
    }

    private void degradeAfterFailure(String reason, RuntimeException exception) {
        safeMarkDegraded(reason);
        LOGGER.log(
                System.Logger.Level.ERROR,
                "event=identity_presence_bloom_update_failed reason=" + reason,
                exception);
        scheduleRebuild(60L);
    }

    private void safeMarkDegraded(String reason) {
        observeDegraded(reason);
        try {
            store.markDegraded(reason);
        } catch (RuntimeException degradationFailure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=identity_presence_bloom_degraded_state_write_failed",
                    degradationFailure);
        }
    }

    private void observeDegraded(String reason) {
        try {
            observer.degraded(reason);
        } catch (RuntimeException observationFailure) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "event=identity_presence_bloom_degraded_metric_failed",
                    observationFailure);
        }
    }

    private HmacIdentifier protect(String purpose, String normalizedValue) {
        return hmac.identify(
                purpose, normalizedValue.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireNormalizedEmail(String email) {
        if (email == null
                || email.isBlank()
                || !email.equals(email.trim())
                || !email.equals(email.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Bloom email must already be normalized.");
        }
    }

    private static void requireE164Phone(String phone) {
        if (phone == null || !phone.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new IllegalArgumentException("Bloom phone must use normalized E.164 format.");
        }
    }

    private static final class BloomBuildException extends RuntimeException {

        private final IdentityPresenceMutationResult result;

        private BloomBuildException(IdentityPresenceMutationResult result) {
            super("Identity Bloom build mutation failed: " + result);
            this.result = result;
        }
    }
}
