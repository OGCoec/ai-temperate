package com.example.temperate.service.admin.mailinspection.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobReservationStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionJobStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.job.impl.RedisAdminMailInspectionJobStore;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobCodec;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionRedisJobDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 使用隔离 Redis 容器验证邮件任务预留、幂等重放、结果去重、revision 与终态统一过期语义。
 */
@Testcontainers(disabledWithoutDocker = true)
final class RedisAdminMailInspectionJobStoreIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault(
                    "AIT_TEST_REDIS_IMAGE",
                    "redis:7.4.9-alpine");

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final AdminMailInspectionProperties properties =
            AdminMailInspectionProperties.defaults();
    private final HybridBase64UrlCodec idCodec =
            new HybridBase64UrlCodec();
    private final RedisKeyFactory keyFactory =
            new RedisKeyFactory("test");
    private final Clock clock = Clock.systemUTC();

    private MailInspectionJobKeyHasher keyHasher;
    private RedisAdminMailInspectionJobStore store;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(),
                REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectFromRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushAll();
        }
        keyHasher = new MailInspectionJobKeyHasher(properties, idCodec);
        store = new RedisAdminMailInspectionJobStore(
                redisTemplate,
                keyFactory,
                keyHasher,
                new MailInspectionRedisJobCodec(new ObjectMapper()),
                properties,
                ignored -> { },
                clock);
        store.changeAcceptanceState(
                MailInspectionType.OPENAI_STATUS,
                MailInspectionAcceptanceState.ACCEPTING,
                "TEST_READY");
    }

    @Test
    void reservesReplaysAndCompletesWithOneAbsoluteTerminalDeadline() {
        Instant now = clock.instant();
        String jobId = id(1);
        MailInspectionRedisJobDocument candidate =
                candidate(jobId, now);

        var created = store.reserveOrFind(candidate, List.of());
        var replayed = store.reserveOrFind(
                candidate(id(2), now),
                List.of());

        assertThat(created.status())
                .isEqualTo(MailInspectionJobReservationStatus.CREATED);
        assertThat(replayed.status())
                .isEqualTo(MailInspectionJobReservationStatus.REPLAYED);
        assertThat(replayed.document().jobId()).isEqualTo(jobId);
        HmacIdentifier jobHash = keyHasher.hashJobId(jobId);
        String pendingItemsJson = (String) redisTemplate.opsForHash().get(
                keyFactory.adminMailInspectionJobMetaKey(jobHash),
                "pendingItems");
        assertThat(pendingItemsJson)
                .contains("\"schemaVersion\":2")
                .contains("\"items\":[]");
        assertThat(store.claimLine(jobId, 1, clock.instant())).isTrue();
        assertThat(store.recordResult(
                jobId,
                MailInspectionResult.inputFailure(
                        1,
                        "masked@example.test",
                        MailInspectionResultStatus.INVALID_EMAIL,
                        "test_result"),
                clock.instant())).isTrue();
        assertThat(store.recordResult(
                jobId,
                MailInspectionResult.inputFailure(
                        1,
                        "masked@example.test",
                        MailInspectionResultStatus.INVALID_EMAIL,
                        "duplicate_result"),
                clock.instant())).isFalse();

        var snapshot = store.findSnapshot(jobId).orElseThrow();
        assertThat(snapshot.status())
                .isEqualTo(MailInspectionJobStatus.COMPLETED);
        assertThat(snapshot.revision()).isGreaterThanOrEqualTo(3L);
        assertThat(snapshot.results()).hasSize(1);
        assertThat(snapshot.summary().counts())
                .containsEntry(MailInspectionResultStatus.INVALID_EMAIL, 1);
        assertThat(terminalTtls(jobId))
                .allSatisfy(ttl -> assertThat(ttl)
                        .isPositive()
                        .isLessThanOrEqualTo(
                                properties.job()
                                        .terminalRetention()
                                        .toMillis()))
                .satisfies(ttls ->
                        assertThat(ttls.stream()
                                .mapToLong(Long::longValue)
                                .max()
                                .orElseThrow()
                                - ttls.stream()
                                .mapToLong(Long::longValue)
                                .min()
                                .orElseThrow())
                                .isLessThanOrEqualTo(1_000L));
    }

    @Test
    void refreshesActiveLeaseInBatchWhileReadsNeverExtendIt() {
        Instant now = clock.instant();
        String jobId = id(3);
        store.reserveOrFind(candidate(jobId, now), List.of());
        HmacIdentifier hash = keyHasher.hashJobId(jobId);
        String metaKey = keyFactory.adminMailInspectionJobMetaKey(hash);
        redisTemplate.expire(metaKey, java.time.Duration.ofSeconds(2));

        store.refreshActiveLeases();
        long renewed = ttl(metaKey);
        store.findSnapshot(jobId).orElseThrow();
        long afterRead = ttl(metaKey);

        assertThat(renewed)
                .isGreaterThan(
                        properties.job().activeLease().minusSeconds(5)
                                .toMillis());
        assertThat(afterRead).isLessThanOrEqualTo(renewed);
    }

    @Test
    void rejectsMutationForAnUnknownJobWithoutCreatingRedisState() {
        String missingJobId = id(9);

        assertThatThrownBy(() ->
                store.claimLine(missingJobId, 1, clock.instant()))
                .isInstanceOf(AdminException.class);
        HmacIdentifier hash = keyHasher.hashJobId(missingJobId);
        assertThat(redisTemplate.hasKey(
                keyFactory.adminMailInspectionJobMetaKey(hash))).isFalse();
        assertThat(redisTemplate.hasKey(
                keyFactory.adminMailInspectionJobCountsKey(hash))).isFalse();
        assertThat(redisTemplate.hasKey(
                keyFactory.adminMailInspectionJobRevisionKey(hash))).isFalse();
    }

    @Test
    void failsClosedWhenRevisionIsMissingFromAnExistingJobDocument() {
        String jobId = id(5);
        store.reserveOrFind(candidate(jobId, clock.instant()), List.of());
        HmacIdentifier hash = keyHasher.hashJobId(jobId);
        redisTemplate.delete(
                keyFactory.adminMailInspectionJobRevisionKey(hash));

        assertThatThrownBy(() -> store.findSnapshotMeta(jobId))
                .isInstanceOf(AdminException.class);
    }

    @Test
    void readsHeartbeatMetadataInOneBatchAndSkipsUnknownJobs() {
        String jobId = id(8);
        store.reserveOrFind(candidate(jobId, clock.instant()), List.of());

        var documents = store.findSnapshotMetas(
                Set.of(jobId, id(9)));

        assertThat(documents).containsOnlyKeys(jobId);
        assertThat(documents.get(jobId).jobId()).isEqualTo(jobId);
    }

    @Test
    void failsClosedWhenActiveIndexAndDocumentIdentityDisagree() {
        String jobId = id(7);
        store.reserveOrFind(candidate(jobId, clock.instant()), List.of());
        HmacIdentifier hash = keyHasher.hashJobId(jobId);
        redisTemplate.opsForHash().put(
                keyFactory.adminMailInspectionJobMetaKey(hash),
                "jobHash",
                "Z".repeat(43));

        assertThatThrownBy(store::refreshActiveLeases)
                .isInstanceOf(AdminException.class);
    }

    @Test
    void rejectsFreshDuplicateClaimButReclaimsAStaleInflightLine() {
        String jobId = id(4);
        store.reserveOrFind(candidate(jobId, clock.instant()), List.of());
        HmacIdentifier hash = keyHasher.hashJobId(jobId);
        String countsKey =
                keyFactory.adminMailInspectionJobCountsKey(hash);

        assertThat(store.claimLine(jobId, 1, clock.instant())).isTrue();
        assertThat(store.claimLine(jobId, 1, clock.instant())).isFalse();
        redisTemplate.opsForHash().put(countsKey, "inflight:1", "0");

        assertThat(store.claimLine(jobId, 1, clock.instant())).isTrue();
        assertThat(store.findSnapshot(jobId).orElseThrow().runningCount())
                .isEqualTo(1);
    }

    @Test
    void restoresKnownResultsWithStatusCountsAndRemainingQueue() {
        Instant now = clock.instant();
        String jobId = id(6);
        MailInspectionRedisJobDocument recovered = recoveredCandidate(
                jobId,
                now);
        List<MailInspectionResult> knownResults = List.of(
                MailInspectionResult.inputFailure(
                        1,
                        "masked-one@example.test",
                        MailInspectionResultStatus.INVALID_EMAIL,
                        "invalid_email"),
                MailInspectionResult.inputFailure(
                        3,
                        "masked-three@example.test",
                        MailInspectionResultStatus.DUPLICATE_EMAIL,
                        "duplicate_email"));

        store.restorePendingJob(recovered, knownResults);
        store.restorePendingJob(recovered, knownResults);

        var snapshot = store.findSnapshot(jobId).orElseThrow();
        assertThat(snapshot.processedCount()).isEqualTo(2);
        assertThat(snapshot.queuedCount()).isEqualTo(1);
        assertThat(snapshot.results()).hasSize(2);
        assertThat(snapshot.summary().counts())
                .containsEntry(MailInspectionResultStatus.INVALID_EMAIL, 1)
                .containsEntry(MailInspectionResultStatus.DUPLICATE_EMAIL, 1);
    }

    private MailInspectionRedisJobDocument candidate(
            String jobId, Instant now) {
        return new MailInspectionRedisJobDocument(
                MailInspectionRedisJobDocument.SCHEMA_VERSION,
                jobId,
                keyHasher.hashJobId(jobId).value(),
                MailInspectionType.OPENAI_STATUS,
                MailInspectionJobStatus.DISPATCHING,
                1,
                1,
                0,
                0,
                4,
                1,
                "550e8400-e29b-41d4-a716-446655440000",
                "F".repeat(43),
                1,
                false,
                false,
                0,
                false,
                List.of(),
                now,
                null,
                null,
                now.plus(properties.job().activeLease()),
                now.plus(properties.submission().incompleteRetention()),
                null,
                0L);
    }

    private MailInspectionRedisJobDocument recoveredCandidate(
            String jobId, Instant now) {
        return new MailInspectionRedisJobDocument(
                MailInspectionRedisJobDocument.SCHEMA_VERSION,
                jobId,
                keyHasher.hashJobId(jobId).value(),
                MailInspectionType.OPENAI_STATUS,
                MailInspectionJobStatus.RUNNING,
                3,
                1,
                1,
                1,
                4,
                3,
                null,
                null,
                0,
                true,
                false,
                0,
                false,
                List.of(),
                now,
                now,
                null,
                now.plus(properties.job().activeLease()),
                null,
                now,
                7L);
    }

    private List<Long> terminalTtls(String jobId) {
        HmacIdentifier jobHash = keyHasher.hashJobId(jobId);
        HmacIdentifier requestHash = keyHasher.hashClientRequestId(
                "550e8400-e29b-41d4-a716-446655440000");
        return List.of(
                ttl(keyFactory.adminMailInspectionJobMetaKey(jobHash)),
                ttl(keyFactory.adminMailInspectionJobCountsKey(jobHash)),
                ttl(keyFactory.adminMailInspectionJobRevisionKey(jobHash)),
                ttl(keyFactory.adminMailInspectionJobResultBucketKey(
                        jobHash,
                        0)),
                ttl(keyFactory.adminMailInspectionJobIdempotencyKey(
                        requestHash)));
    }

    private static long ttl(String key) {
        Long value = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        return value == null ? -2L : value;
    }

    private String id(int discriminator) {
        byte[] bytes = new byte[HybridBase64UrlCodec.BINARY_LENGTH];
        bytes[bytes.length - 1] = (byte) discriminator;
        return idCodec.encode(bytes);
    }
}
