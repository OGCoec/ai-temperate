package com.example.temperate.service.risk.preauth.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.admin.config.properties.AdminProperties;
import com.example.temperate.service.admin.security.AdminSecretProtector;
import com.example.temperate.service.admin.session.impl.RedisAdminSessionStore;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.ReportDecisionCommand;
import com.example.temperate.service.auth.oauth.webrtc.store.impl.RedisOAuthWebRtcAttemptStore;
import com.example.temperate.service.auth.session.refresh.dto.command.NewRefreshSession;
import com.example.temperate.service.auth.session.refresh.dto.result.RefreshSessionValidation;
import com.example.temperate.service.auth.session.refresh.store.impl.RedisRefreshSessionStore;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.preauth.store.impl.RedisPreAuthStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 在临时 Redis 7.4 中验证 PreAuth v7 WebRTC 四态 Lua 以及普通、管理员会话绑定。
 *
 * <p>测试只写容器 Redis，不连接 PostgreSQL、生产 Redis 或外部服务；deadline 断言统一读取 Redis
 * TIME，避免 Java 节点时钟成为状态机的隐式输入。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisPreAuthWebRtcStateMachineIntegrationTest {

    private static final String REDIS_IMAGE =
            System.getenv().getOrDefault("AIT_TEST_REDIS_IMAGE", "redis:7.4.9-alpine");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration START_GRACE = Duration.ofSeconds(8);
    private static final Duration REPORT_WINDOW = Duration.ofSeconds(15);
    private static final RedisKeyFactory KEYS = new RedisKeyFactory("test");
    private static final PublicIdCodec PUBLIC_IDS = new PublicIdCodec();
    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "preauth-v6-integration-secret-0123456789"
                    .getBytes(StandardCharsets.UTF_8));

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RedisPreAuthStore store;
    private RedisOAuthWebRtcAttemptStore oauthStore;

    @BeforeAll
    static void connectToRedis() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
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
        store = new RedisPreAuthStore(redisTemplate, KEYS);
        oauthStore = new RedisOAuthWebRtcAttemptStore(redisTemplate, KEYS);
    }

    @Test
    void createUsesRedisTimeAndBeginIsIdempotentWithoutExtendingDeadline() {
        Identifiers ids = identifiers("create");
        long beforeCreate = redisNowMillis();

        assertThat(create(RiskScope.USER, ids, START_GRACE)).isTrue();

        long afterCreate = redisNowMillis();
        PreAuthState required = requiredState(RiskScope.USER, ids.token());
        assertThat(required.schemaVersion()).isEqualTo(PreAuthState.CURRENT_SCHEMA_VERSION);
        assertThat(required.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.REQUIRED);
        assertThat(required.webRtcGeneration()).isEqualTo(1L);
        assertThat(required.webRtcDeadlineAt().toEpochMilli()).isBetween(
                beforeCreate + START_GRACE.toMillis(),
                afterCreate + START_GRACE.toMillis());

        PreAuthWebRtcBeginResult first = begin(RiskScope.USER, ids, 1L);
        PreAuthWebRtcBeginResult repeated = begin(RiskScope.USER, ids, 1L);

        assertThat(first.status()).isEqualTo(PreAuthWebRtcBeginResult.Status.STARTED);
        assertThat(repeated.status())
                .isEqualTo(PreAuthWebRtcBeginResult.Status.PENDING_PRESERVED);
        assertThat(repeated.deadlineAt()).isEqualTo(first.deadlineAt());
        assertThat(repeated.remainingMillis()).isLessThanOrEqualTo(first.remainingMillis());
        assertThat(requiredState(RiskScope.USER, ids.token()).webRtcPhase())
                .isEqualTo(PreAuthWebRtcPhase.PENDING);
    }

    @Test
    void reportSuccessAndMismatchBecomeTerminalAndCannotOverwriteEachOther() {
        Identifiers success = identifiers("success");
        create(RiskScope.USER, success, START_GRACE);
        begin(RiskScope.USER, success, 1L);

        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        success.token(),
                        success.device(),
                        success.ip(),
                        1L,
                        true,
                        null,
                        "encrypted-success-candidates",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.UPDATED);
        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        success.token(),
                        success.device(),
                        success.ip(),
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.IP_MISMATCH,
                        "encrypted-late-mismatch",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.VERIFIED_PRESERVED);
        PreAuthState verified = requiredState(RiskScope.USER, success.token());
        assertThat(verified.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.VERIFIED);
        assertThat(verified.webRtcIps()).isEqualTo("encrypted-success-candidates");

        Identifiers mismatch = identifiers("mismatch");
        create(RiskScope.USER, mismatch, START_GRACE);
        begin(RiskScope.USER, mismatch, 1L);
        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        mismatch.token(),
                        mismatch.device(),
                        mismatch.ip(),
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.IP_MISMATCH,
                        "encrypted-mismatch-candidates",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.UPDATED);
        PreAuthState failed = requiredState(RiskScope.USER, mismatch.token());
        assertThat(failed.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.FAILED);
        assertThat(failed.webRtcFailureReason())
                .isEqualTo(PreAuthWebRtcFailureReason.IP_MISMATCH);
        assertThat(failed.webRtcIps()).isEqualTo("encrypted-mismatch-candidates");

        Identifiers familyIncomplete = identifiers("family-incomplete");
        create(RiskScope.USER, familyIncomplete, START_GRACE);
        begin(RiskScope.USER, familyIncomplete, 1L);
        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        familyIncomplete.token(),
                        familyIncomplete.device(),
                        familyIncomplete.ip(),
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.IP_FAMILY_INCOMPLETE,
                        "encrypted-opposite-family-candidates",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.UPDATED);
        PreAuthState incomplete = requiredState(
                RiskScope.USER,
                familyIncomplete.token());
        assertThat(incomplete.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.FAILED);
        assertThat(incomplete.webRtcFailureReason())
                .isEqualTo(PreAuthWebRtcFailureReason.IP_FAMILY_INCOMPLETE);
        assertThat(incomplete.webRtcIps())
                .isEqualTo("encrypted-opposite-family-candidates");
    }

    @Test
    void requiredAndPendingUseDifferentTerminalTimeoutReasons() {
        Identifiers requiredIds = identifiers("start-timeout");
        create(RiskScope.USER, requiredIds, START_GRACE);
        forceDeadlineBeforeRedisNow(RiskScope.USER, requiredIds.token());

        assertThat(begin(RiskScope.USER, requiredIds, 1L).status())
                .isEqualTo(PreAuthWebRtcBeginResult.Status.START_TIMEOUT);
        assertThat(requiredState(RiskScope.USER, requiredIds.token()).webRtcFailureReason())
                .isEqualTo(PreAuthWebRtcFailureReason.START_TIMEOUT);

        Identifiers pendingIds = identifiers("report-timeout");
        create(RiskScope.USER, pendingIds, START_GRACE);
        begin(RiskScope.USER, pendingIds, 1L);
        forceDeadlineBeforeRedisNow(RiskScope.USER, pendingIds.token());

        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        pendingIds.token(),
                        pendingIds.device(),
                        pendingIds.ip(),
                        1L,
                        true,
                        null,
                        "encrypted-late-candidates",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.DEADLINE_EXPIRED);
        PreAuthState reportTimedOut = requiredState(RiskScope.USER, pendingIds.token());
        assertThat(reportTimedOut.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.FAILED);
        assertThat(reportTimedOut.webRtcFailureReason())
                .isEqualTo(PreAuthWebRtcFailureReason.REPORT_TIMEOUT);
        assertThat(reportTimedOut.webRtcIps()).isNull();
    }

    @Test
    void ipChangeIncrementsGenerationAndRejectsTheOldReport() {
        Identifiers ids = identifiers("network-change");
        create(RiskScope.USER, ids, START_GRACE);
        begin(RiskScope.USER, ids, 1L);
        HmacIdentifier nextIp = id("ip-network-change-next");

        assertThat(store.recordAssessment(
                        RiskScope.USER,
                        ids.token(),
                        ids.device(),
                        snapshot(nextIp),
                        RiskDecision.ALLOW,
                        Instant.EPOCH.plusSeconds(1),
                        id("context-network-change-next"),
                        null,
                        true,
                        START_GRACE,
                        TTL))
                .isTrue();

        PreAuthState changed = requiredState(RiskScope.USER, ids.token());
        assertThat(changed.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.REQUIRED);
        assertThat(changed.webRtcGeneration()).isEqualTo(2L);
        assertThat(changed.webRtcIps()).isNull();
        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        ids.token(),
                        ids.device(),
                        ids.ip(),
                        1L,
                        true,
                        null,
                        "encrypted-old-generation",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.NETWORK_CHANGED);
    }

    @Test
    void loginRotationPreservesOnlyVerifiedAndRestartsEveryOpenState() {
        Identifiers verifiedIds = identifiers("rotate-verified");
        create(RiskScope.USER, verifiedIds, START_GRACE);
        begin(RiskScope.USER, verifiedIds, 1L);
        store.writeWebRtcResult(
                RiskScope.USER,
                verifiedIds.token(),
                verifiedIds.device(),
                verifiedIds.ip(),
                1L,
                true,
                null,
                "encrypted-old-verified",
                true,
                TTL);
        HmacIdentifier verifiedTarget = id("rotate-verified-target");

        assertThat(store.rotateAuthenticated(
                        RiskScope.USER,
                        verifiedIds.token(),
                        verifiedTarget,
                        verifiedIds.device(),
                        RiskSessionType.USER_REFRESH,
                        id("rotate-verified-session"),
                        id("rotate-verified-context"),
                        PreAuthWebRtcPhase.VERIFIED,
                        1L,
                        PreAuthWebRtcPhase.VERIFIED,
                        1L,
                        "encrypted-new-verified",
                        Instant.EPOCH.plusSeconds(2),
                        START_GRACE,
                        TTL))
                .isTrue();
        PreAuthState inherited = requiredState(RiskScope.USER, verifiedTarget);
        assertThat(inherited.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.VERIFIED);
        assertThat(inherited.webRtcIps()).isEqualTo("encrypted-new-verified");

        Identifiers pendingIds = identifiers("rotate-pending");
        create(RiskScope.USER, pendingIds, START_GRACE);
        begin(RiskScope.USER, pendingIds, 1L);
        HmacIdentifier pendingTarget = id("rotate-pending-target");

        assertThat(store.rotateAuthenticated(
                        RiskScope.USER,
                        pendingIds.token(),
                        pendingTarget,
                        pendingIds.device(),
                        RiskSessionType.USER_REFRESH,
                        id("rotate-pending-session"),
                        id("rotate-pending-context"),
                        PreAuthWebRtcPhase.PENDING,
                        1L,
                        PreAuthWebRtcPhase.REQUIRED,
                        2L,
                        null,
                        Instant.EPOCH.plusSeconds(2),
                        START_GRACE,
                        TTL))
                .isTrue();
        PreAuthState restarted = requiredState(RiskScope.USER, pendingTarget);
        assertThat(restarted.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.REQUIRED);
        assertThat(restarted.webRtcGeneration()).isEqualTo(2L);
        assertThat(restarted.webRtcDeadlineAt()).isNotNull();
        assertThat(restarted.webRtcIps()).isNull();

        Identifiers invalidVerifiedIds = identifiers("rotate-invalid-verified");
        create(RiskScope.USER, invalidVerifiedIds, START_GRACE);
        begin(RiskScope.USER, invalidVerifiedIds, 1L);
        store.writeWebRtcResult(
                RiskScope.USER,
                invalidVerifiedIds.token(),
                invalidVerifiedIds.device(),
                invalidVerifiedIds.ip(),
                1L,
                true,
                null,
                "encrypted-source-evidence",
                true,
                TTL);
        HmacIdentifier fallbackTarget = id("rotate-invalid-verified-target");

        assertThat(store.rotateAuthenticated(
                        RiskScope.USER,
                        invalidVerifiedIds.token(),
                        fallbackTarget,
                        invalidVerifiedIds.device(),
                        RiskSessionType.USER_REFRESH,
                        id("rotate-invalid-verified-session"),
                        id("rotate-invalid-verified-context"),
                        PreAuthWebRtcPhase.VERIFIED,
                        1L,
                        PreAuthWebRtcPhase.REQUIRED,
                        2L,
                        null,
                        Instant.EPOCH.plusSeconds(2),
                        START_GRACE,
                        TTL))
                .isTrue();
        PreAuthState fallback = requiredState(RiskScope.USER, fallbackTarget);
        assertThat(fallback.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.REQUIRED);
        assertThat(fallback.webRtcGeneration()).isEqualTo(2L);
        assertThat(fallback.webRtcIps()).isNull();
    }

    @Test
    void genericReportCannotClaimOAuthOwnedPendingGeneration() {
        Identifiers ids = identifiers("oauth-owner-block");
        create(RiskScope.USER, ids, START_GRACE);
        begin(RiskScope.USER, ids, 1L);
        redisTemplate.opsForHash().putAll(
                KEYS.userPreAuthKey(ids.token()),
                Map.of(
                        "webRtcOwner", "OAUTH",
                        "oauthWebRtcAttemptDigest", id("oauth-owner-attempt").value()));

        assertThat(store.writeWebRtcResult(
                        RiskScope.USER,
                        ids.token(),
                        ids.device(),
                        ids.ip(),
                        1L,
                        true,
                        null,
                        "encrypted-generic-candidates",
                        true,
                        TTL))
                .isEqualTo(PreAuthWebRtcWriteResult.OAUTH_ATTEMPT_REQUIRED);
        assertThat(requiredState(RiskScope.USER, ids.token()).webRtcPhase())
                .isEqualTo(PreAuthWebRtcPhase.PENDING);
    }

    @Test
    void verifiedPreAuthAndResumedAttemptConvergePendingSessionToActive() {
        Identifiers ids = identifiers("oauth-verified-convergence");
        HmacIdentifier attempt = id("oauth-verified-convergence-attempt");
        HmacIdentifier refresh = id("oauth-verified-convergence-refresh");
        String preAuthKey = KEYS.userPreAuthKey(ids.token());
        String attemptKey = KEYS.oauthWebRtcAttemptKey(attempt);
        String refreshKey = KEYS.sessionRefreshTokenKey(refresh);
        long deadline = redisNowMillis() + Duration.ofSeconds(15).toMillis();
        create(RiskScope.USER, ids, START_GRACE);
        begin(RiskScope.USER, ids, 1L);
        redisTemplate.opsForHash().putAll(preAuthKey, Map.of(
                "webRtcPhase", "VERIFIED",
                "webRtcIps", "encrypted-generic-candidates",
                "webRtcOwner", "OAUTH",
                "oauthWebRtcAttemptDigest", attempt.value()));
        redisTemplate.opsForHash().putAll(attemptKey, Map.of(
                "generation", "1",
                "preAuthTokenDigest", ids.token().value(),
                "deviceDigest", ids.device().value(),
                "currentIpDigest", ids.ip().value(),
                "status", "RESUMED",
                "verdictDeadlineAt", Long.toString(deadline),
                "refreshTokenDigest", refresh.value()));
        redisTemplate.opsForHash().putAll(refreshKey, Map.of(
                "userId", "10001",
                "riskVerdict", "PENDING",
                "riskVerdictAttemptId", attempt.value(),
                "riskVerdictGeneration", "1",
                "riskVerdictDeadlineAt", Long.toString(deadline)));

        assertThat(oauthStore.decideReport(new ReportDecisionCommand(
                        RiskScope.USER,
                        ids.token(),
                        attempt,
                        ids.device(),
                        ids.ip(),
                        1L,
                        true,
                        null,
                        "encrypted-oauth-candidates",
                        true,
                        TTL)))
                .isEqualTo(PreAuthWebRtcWriteResult.UPDATED);
        assertThat(redisTemplate.opsForHash().get(refreshKey, "riskVerdict"))
                .isEqualTo("ACTIVE");
        assertThat(redisTemplate.opsForHash().get(attemptKey, "status"))
                .isEqualTo("VERIFIED");
        assertThat(redisTemplate.opsForHash().hasKey(preAuthKey, "webRtcOwner"))
                .isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(
                        preAuthKey, "oauthWebRtcAttemptDigest"))
                .isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(
                        refreshKey, "riskVerdictDeadlineAt"))
                .isFalse();

        // ACTIVE 会话已经脱离十五秒窗口；同一 report 只做幂等读取，不能重新写入截止时间。
        assertThat(oauthStore.decideReport(new ReportDecisionCommand(
                        RiskScope.USER,
                        ids.token(),
                        attempt,
                        ids.device(),
                        ids.ip(),
                        1L,
                        true,
                        null,
                        "encrypted-oauth-candidates",
                        true,
                        TTL)))
                .isEqualTo(PreAuthWebRtcWriteResult.VERIFIED_PRESERVED);
        assertThat(redisTemplate.opsForHash().get(refreshKey, "riskVerdict"))
                .isEqualTo("ACTIVE");
        assertThat(redisTemplate.opsForHash().hasKey(
                        refreshKey, "riskVerdictDeadlineAt"))
                .isFalse();
    }

    @Test
    void failedOAuthReportRevokesThePendingRefreshSessionAndOwner() {
        Identifiers ids = identifiers("oauth-failed-verdict");
        HmacIdentifier attempt = id("oauth-failed-verdict-attempt");
        HmacIdentifier refresh = id("oauth-failed-verdict-refresh");
        String preAuthKey = KEYS.userPreAuthKey(ids.token());
        String attemptKey = KEYS.oauthWebRtcAttemptKey(attempt);
        String refreshKey = KEYS.sessionRefreshTokenKey(refresh);
        String userIndexKey = KEYS.sessionUserIndexKey(10002L);
        long deadline = redisNowMillis() + Duration.ofSeconds(15).toMillis();
        create(RiskScope.USER, ids, START_GRACE);
        begin(RiskScope.USER, ids, 1L);
        redisTemplate.opsForHash().putAll(preAuthKey, Map.of(
                "webRtcOwner", "OAUTH",
                "oauthWebRtcAttemptDigest", attempt.value()));
        redisTemplate.opsForHash().putAll(attemptKey, Map.of(
                "generation", "1",
                "preAuthTokenDigest", ids.token().value(),
                "deviceDigest", ids.device().value(),
                "currentIpDigest", ids.ip().value(),
                "status", "RESUMED",
                "verdictDeadlineAt", Long.toString(deadline),
                "refreshTokenDigest", refresh.value()));
        redisTemplate.opsForHash().putAll(refreshKey, Map.of(
                "userId", "10002",
                "riskVerdict", "PENDING",
                "riskVerdictAttemptId", attempt.value(),
                "riskVerdictGeneration", "1",
                "riskVerdictDeadlineAt", Long.toString(deadline)));
        redisTemplate.opsForHash().put(userIndexKey, refresh.value(), refreshKey);

        assertThat(oauthStore.decideReport(new ReportDecisionCommand(
                        RiskScope.USER,
                        ids.token(),
                        attempt,
                        ids.device(),
                        ids.ip(),
                        1L,
                        false,
                        PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE,
                        null,
                        false,
                        TTL)))
                .isEqualTo(PreAuthWebRtcWriteResult.UPDATED);
        assertThat(requiredState(RiskScope.USER, ids.token()).webRtcPhase())
                .isEqualTo(PreAuthWebRtcPhase.FAILED);
        assertThat(redisTemplate.hasKey(refreshKey)).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(userIndexKey, refresh.value()))
                .isFalse();
        assertThat(redisTemplate.opsForHash().get(attemptKey, "status"))
                .isEqualTo("FAILED");
        assertThat(redisTemplate.opsForHash().hasKey(preAuthKey, "webRtcOwner"))
                .isFalse();
    }

    @Test
    void strictOAuthRotationRequiresUnchangedVerifiedContextAtomically() {
        Identifiers verified = identifiers("strict-oauth-verified");
        create(RiskScope.USER, verified, START_GRACE);
        begin(RiskScope.USER, verified, 1L);
        store.writeWebRtcResult(
                RiskScope.USER,
                verified.token(),
                verified.device(),
                verified.ip(),
                1L,
                true,
                null,
                "encrypted-source",
                true,
                TTL);
        HmacIdentifier target = id("strict-oauth-target");

        assertThat(store.rotateAuthenticatedAfterWebRtcVerified(
                        RiskScope.USER,
                        verified.token(),
                        target,
                        verified.device(),
                        verified.ip(),
                        id("context-" + verified.suffix()),
                        RiskSessionType.USER_REFRESH,
                        id("strict-oauth-session"),
                        id("strict-oauth-new-context"),
                        1L,
                        "encrypted-target",
                        Instant.EPOCH.plusSeconds(2),
                        TTL))
                .isTrue();
        PreAuthState promoted = requiredState(RiskScope.USER, target);
        assertThat(promoted.webRtcPhase()).isEqualTo(PreAuthWebRtcPhase.VERIFIED);
        assertThat(promoted.webRtcGeneration()).isEqualTo(1L);
        assertThat(promoted.webRtcIps()).isEqualTo("encrypted-target");

        Identifiers changed = identifiers("strict-oauth-changed");
        create(RiskScope.USER, changed, START_GRACE);
        begin(RiskScope.USER, changed, 1L);
        store.writeWebRtcResult(
                RiskScope.USER,
                changed.token(),
                changed.device(),
                changed.ip(),
                1L,
                true,
                null,
                "encrypted-source",
                true,
                TTL);
        HmacIdentifier rejectedTarget = id("strict-oauth-rejected-target");

        assertThat(store.rotateAuthenticatedAfterWebRtcVerified(
                        RiskScope.USER,
                        changed.token(),
                        rejectedTarget,
                        changed.device(),
                        changed.ip(),
                        id("different-context"),
                        RiskSessionType.USER_REFRESH,
                        id("strict-oauth-session"),
                        id("strict-oauth-new-context"),
                        1L,
                        "encrypted-target",
                        Instant.EPOCH.plusSeconds(2),
                        TTL))
                .isFalse();
        assertThat(store.find(RiskScope.USER, changed.token())).isPresent();
        assertThat(store.find(RiskScope.USER, rejectedTarget)).isEmpty();
    }

    @Test
    void userAndAdminSessionLuaAcceptSchemaV6PreAuthBindings() {
        verifyUserSessionBinding();
        verifyAdminSessionBinding();
    }

    private void verifyUserSessionBinding() {
        Identifiers ids = identifiers("user-session");
        create(RiskScope.USER, ids, START_GRACE);
        HmacIdentifier refresh = id("user-refresh");
        HmacIdentifier sessionDevice = id("user-session-device");
        HmacIdentifier csrf = id("user-csrf");
        RedisRefreshSessionStore refreshStore = new RedisRefreshSessionStore(
                redisTemplate,
                KEYS,
                PUBLIC_IDS,
                Duration.ofHours(3),
                10,
                100);
        refreshStore.create(new NewRefreshSession(
                10001L,
                PUBLIC_IDS.encode(10001L),
                refresh,
                sessionDevice,
                csrf,
                "person@example.test",
                "+8613812345678"));
        PreAuthSessionBinding binding = new PreAuthSessionBinding(
                RiskScope.USER,
                ids.token(),
                ids.device(),
                RiskSessionType.USER_REFRESH,
                refresh,
                TTL,
                true);

        RefreshSessionValidation result = refreshStore.bootstrapAndRenewWithPreAuth(
                refresh,
                sessionDevice,
                id("user-new-csrf"),
                binding);

        assertThat(result.status()).isEqualTo(RefreshSessionValidation.Status.VALID);
        PreAuthState promoted = requiredState(RiskScope.USER, ids.token());
        assertThat(promoted.authState()).isEqualTo("AUTHENTICATED");
        assertThat(promoted.sessionType()).isEqualTo(RiskSessionType.USER_REFRESH);
    }

    private void verifyAdminSessionBinding() {
        Identifiers ids = identifiers("admin-session");
        create(RiskScope.ADMIN, ids, START_GRACE);
        AdminSecretProtector protector = new AdminSecretProtector(
                AdminProperties.testDefaults(Path.of("admin-test.yml")));
        RedisAdminSessionStore adminStore = new RedisAdminSessionStore(
                redisTemplate,
                KEYS,
                protector,
                new ObjectMapper().findAndRegisterModules());
        String rawToken = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZab";
        String rawDevice = "d74db03f-c24a-46a8-b103-0db98233a16c";
        Instant now = Instant.EPOCH.plusSeconds(10);
        adminStore.create(rawToken, rawDevice, now, Duration.ofHours(3), 10);
        HmacIdentifier sessionReference = protector.sessionToken(rawToken);
        PreAuthSessionBinding binding = new PreAuthSessionBinding(
                RiskScope.ADMIN,
                ids.token(),
                ids.device(),
                RiskSessionType.ADMIN_SESSION,
                sessionReference,
                TTL,
                true);

        adminStore.touchWithPreAuth(
                rawToken,
                rawDevice,
                now.plusSeconds(1),
                Duration.ofHours(3),
                binding);

        PreAuthState promoted = requiredState(RiskScope.ADMIN, ids.token());
        assertThat(promoted.authState()).isEqualTo("AUTHENTICATED");
        assertThat(promoted.sessionType()).isEqualTo(RiskSessionType.ADMIN_SESSION);
    }

    private boolean create(
            RiskScope scope,
            Identifiers ids,
            Duration startGrace) {
        return store.create(
                scope,
                ids.token(),
                ids.device(),
                snapshot(ids.ip()),
                RiskDecision.ALLOW,
                id("context-" + ids.suffix()),
                null,
                true,
                startGrace,
                TTL);
    }

    private PreAuthWebRtcBeginResult begin(
            RiskScope scope,
            Identifiers ids,
            long generation) {
        return store.beginWebRtcVerification(
                scope,
                ids.token(),
                ids.device(),
                ids.ip(),
                generation,
                REPORT_WINDOW,
                TTL);
    }

    private static PreAuthNetworkSnapshot snapshot(HmacIdentifier ip) {
        return new PreAuthNetworkSnapshot(
                ip,
                80,
                "US",
                64512L,
                BigDecimal.ONE,
                BigDecimal.ONE,
                NetworkType.RESIDENTIAL,
                true,
                PreAuthRiskSource.DEFAULT,
                PreAuthGeoSource.NONE,
                Instant.EPOCH);
    }

    private PreAuthState requiredState(RiskScope scope, HmacIdentifier token) {
        return store.find(scope, token).orElseThrow();
    }

    private void forceDeadlineBeforeRedisNow(
            RiskScope scope,
            HmacIdentifier token) {
        String key = scope == RiskScope.ADMIN
                ? KEYS.adminPreAuthKey(token)
                : KEYS.userPreAuthKey(token);
        redisTemplate.opsForHash().put(
                key,
                "webRtcDeadlineAt",
                Long.toString(redisNowMillis() - 1L));
    }

    private static long redisNowMillis() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(
                "local t = redis.call('TIME') "
                        + "return tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)",
                Long.class);
        Long result = redisTemplate.execute(script, java.util.List.of());
        if (result == null) {
            throw new IllegalStateException("Redis TIME returned no value.");
        }
        return result;
    }

    private static Identifiers identifiers(String suffix) {
        return new Identifiers(
                suffix,
                id("token-" + suffix),
                id("device-" + suffix),
                id("ip-" + suffix));
    }

    private static HmacIdentifier id(String value) {
        return HMAC.identify(value);
    }

    /**
     * 汇总单个测试场景的用途隔离摘要，避免任何原始 Token 或 IP 进入 Redis Key。
     */
    private record Identifiers(
            String suffix,
            HmacIdentifier token,
            HmacIdentifier device,
            HmacIdentifier ip) {
    }
}
