package com.example.temperate.service.auth.oauth.webrtc.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcAttemptService.State;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 使用有界 Redis Hash 和 Lua 实现 OAuth WebRTC attempt 的跨键原子状态机，不保存任何原始令牌或 IP。
 */
@Component
public final class RedisOAuthWebRtcAttemptStore implements OAuthWebRtcAttemptStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SUSPEND = script("suspend_attempt.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESUME = script("resume_attempt.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ISSUE_PENDING = script("issue_pending_session.lua");
    private static final RedisScript<Long> DECIDE_REPORT = longScript("decide_report.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> INSPECT = script("inspect_attempt.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisOAuthWebRtcAttemptStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public SuspendStoreResult suspend(SuspendStoreCommand command) {
        List<?> result = execute(SUSPEND,
                List.of(preAuthKey(command.scope(), command.preAuthTokenDigest()),
                        keyFactory.oauthWebRtcAttemptKey(command.attemptDigest())),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                command.deviceDigest().value(), command.currentIpDigest().value(),
                command.oauthFlowDigest().value(), Long.toString(command.generation()),
                command.probeRunDigest().value(),
                Long.toString(command.suspendExpiresAt().toEpochMilli()));
        int code = number(result, 0).intValue();
        long generation = number(result, 1).longValue();
        return new SuspendStoreResult(
                code == 1 ? State.OAUTH_SUSPENDED
                        : code == 2 ? State.VERIFIED : State.FAILED,
                generation);
    }

    @Override
    public ResumeStoreResult resume(ResumeStoreCommand command) {
        List<?> result = execute(RESUME,
                List.of(preAuthKey(command.scope(), command.preAuthTokenDigest()),
                        keyFactory.oauthWebRtcAttemptKey(command.attemptDigest())),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                command.deviceDigest().value(), command.currentIpDigest().value(),
                command.oauthFlowDigest().value(), Long.toString(command.generation()),
                Long.toString(command.verificationWindow().toMillis()));
        int code = number(result, 0).intValue();
        long generation = number(result, 1).longValue();
        boolean fallback = number(result, 2).longValue() == 1L;
        State state = switch (code) {
            case 1 -> State.RESUMED;
            case 2 -> State.VERIFIED;
            case 3 -> State.REPLACED;
            default -> State.FAILED;
        };
        return new ResumeStoreResult(state, generation, fallback);
    }

    @Override
    public boolean canComplete(AttemptLookup lookup) {
        VerdictStoreResult result = inspect(lookup);
        return result.state() == State.RESUMED;
    }

    @Override
    public PendingStoreResult issuePendingSession(PendingSessionCommand command) {
        List<?> result = execute(ISSUE_PENDING,
                List.of(
                        preAuthKey(command.scope(), command.oldPreAuthTokenDigest()),
                        preAuthKey(command.scope(), command.newPreAuthTokenDigest()),
                        keyFactory.oauthWebRtcAttemptKey(command.attemptDigest()),
                        keyFactory.sessionRefreshTokenKey(command.refreshTokenDigest())),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                command.deviceDigest().value(), command.currentIpDigest().value(),
                command.oauthFlowDigest().value(), Long.toString(command.generation()),
                command.sessionReferenceDigest().value(),
                command.refreshTokenDigest().value(), command.refreshDeviceDigest().value(),
                command.decisionContextDigest().value(), command.sessionType().name(),
                command.seenAt().toString(), Long.toString(command.verdictWindow().toMillis()),
                Long.toString(command.authenticatedPreAuthTtl().toMillis()),
                command.newPreAuthTokenDigest().value(),
                keyFactory.sessionUserIndexKeyPrefix());
        boolean issued = number(result, 0).longValue() == 1L;
        long deadline = number(result, 1).longValue();
        return new PendingStoreResult(
                issued, issued ? Instant.ofEpochMilli(deadline) : null);
    }

    @Override
    public PreAuthWebRtcWriteResult decideReport(ReportDecisionCommand command) {
        Long result = redisTemplate.execute(
                DECIDE_REPORT,
                List.of(preAuthKey(command.scope(), command.preAuthTokenDigest()),
                        keyFactory.oauthWebRtcAttemptKey(command.attemptDigest())),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                command.deviceDigest().value(), command.currentIpDigest().value(),
                Long.toString(command.generation()),
                command.verified() ? "VERIFIED" : "FAILED",
                command.failureReason() == null ? "" : command.failureReason().name(),
                command.encryptedWebRtcIps() == null ? "" : command.encryptedWebRtcIps(),
                command.hasReportedIps() ? "1" : "0",
                Long.toString(command.authenticatedPreAuthTtl().toMillis()),
                keyFactory.sessionRefreshTokenKeyPrefix(),
                keyFactory.sessionUserIndexKeyPrefix());
        return switch (result == null ? 0 : result.intValue()) {
            case 1 -> PreAuthWebRtcWriteResult.UPDATED;
            case 2 -> PreAuthWebRtcWriteResult.VERIFIED_PRESERVED;
            case 3 -> PreAuthWebRtcWriteResult.FAILURE_PRESERVED;
            case 4 -> PreAuthWebRtcWriteResult.DEADLINE_EXPIRED;
            case -1 -> PreAuthWebRtcWriteResult.NETWORK_CHANGED;
            case -2 -> PreAuthWebRtcWriteResult.STALE_GENERATION;
            default -> PreAuthWebRtcWriteResult.PREAUTH_UNAVAILABLE;
        };
    }

    @Override
    public VerdictStoreResult inspect(AttemptLookup lookup) {
        List<?> result = execute(INSPECT,
                List.of(preAuthKey(lookup.scope(), lookup.preAuthTokenDigest()),
                        keyFactory.oauthWebRtcAttemptKey(lookup.attemptDigest())),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                lookup.deviceDigest().value(), lookup.currentIpDigest().value(),
                Long.toString(lookup.generation()));
        State state = state(number(result, 0).intValue());
        long generation = number(result, 1).longValue();
        long deadline = number(result, 2).longValue();
        return new VerdictStoreResult(
                state, generation, deadline > 0 ? Instant.ofEpochMilli(deadline) : null);
    }

    private String preAuthKey(
            com.example.temperate.service.risk.domain.RiskScope scope,
            com.example.temperate.common.security.hmac.HmacIdentifier digest) {
        return scope == com.example.temperate.service.risk.domain.RiskScope.ADMIN
                ? keyFactory.adminPreAuthKey(digest)
                : keyFactory.userPreAuthKey(digest);
    }

    private List<?> execute(RedisScript<List> script, List<String> keys, String... arguments) {
        List<?> result = redisTemplate.execute(script, keys, (Object[]) arguments);
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("OAuth WebRTC Redis state is unavailable.");
        }
        return result;
    }

    private static Number number(List<?> values, int index) {
        if (index >= values.size() || !(values.get(index) instanceof Number number)) {
            throw new IllegalStateException("OAuth WebRTC Redis result is invalid.");
        }
        return number;
    }

    private static State state(int code) {
        return switch (code) {
            case 1 -> State.OAUTH_SUSPENDED;
            case 2 -> State.RESUMED;
            case 3 -> State.VERIFIED;
            case 4 -> State.FAILED;
            case 5 -> State.EXPIRED;
            case 6 -> State.REPLACED;
            default -> State.FAILED;
        };
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script(String name) {
        return RedisScript.of(load(name), List.class);
    }

    private static RedisScript<Long> longScript(String name) {
        return RedisScript.of(load(name), Long.class);
    }

    private static String load(String name) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource("lua/auth-oauth-webrtc/" + name).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("OAuth WebRTC Lua script cannot be loaded.", exception);
        }
    }
}
