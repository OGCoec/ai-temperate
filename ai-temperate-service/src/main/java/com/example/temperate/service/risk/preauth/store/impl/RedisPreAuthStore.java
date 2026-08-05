package com.example.temperate.service.risk.preauth.store.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.ipintel.domain.NetworkType;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.domain.PreAuthGeoSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthRiskSource;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 使用普通与管理员独立的单个 Redis Hash 保存 PreAuth v6 全部有界风险状态。
 *
 * <p>设备校验、事件窗口维护、WebRTC generation/截止时间迁移、Challenge 复用/消费、登录旋转和
 * TTL 刷新均由 Lua 保证各自原子边界，不再创建独立 Travel ZSet 或 Challenge 引用 Key。</p>
 */
@Component
public final class RedisPreAuthStore implements PreAuthStore {

    private static final DateTimeFormatter LUA_COMPARABLE_INSTANT =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final RedisScript<Long> CREATE =
            longScript("lua/network-risk/preauth_create.lua");
    private static final RedisScript<Long> MUTATE =
            longScript("lua/network-risk/preauth_mutate.lua");
    private static final RedisScript<Long> RECORD_ASSESSMENT =
            longScript("lua/network-risk/preauth_record_assessment.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> BEGIN_WEBRTC =
            listScript("lua/network-risk/preauth_begin_webrtc.lua");
    private static final RedisScript<Long> WRITE_WEBRTC =
            longScript("lua/network-risk/preauth_write_webrtc.lua");
    private static final RedisScript<Long> EXPIRE_WEBRTC =
            longScript("lua/network-risk/preauth_expire_webrtc.lua");
    private static final RedisScript<Long> RECORD_EVENT =
            longScript("lua/network-risk/preauth_record_travel_event.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ACTIVATE_CHALLENGE =
            listScript("lua/network-risk/preauth_activate_challenge.lua");
    private static final RedisScript<Long> CONSUME_CHALLENGE =
            longScript("lua/network-risk/preauth_consume_challenge.lua");
    private static final RedisScript<Long> ROTATE_AUTHENTICATED =
            longScript("lua/network-risk/preauth_rotate_authenticated.lua");

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;

    public RedisPreAuthStore(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
    }

    @Override
    public boolean create(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            HmacIdentifier contextDigest,
            Instant temporaryBlockUntil,
            boolean trustCurrent,
            Duration startGrace,
            Duration ttl) {
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(ttl.toMillis()));
        arguments.add(Long.toString(startGrace.toMillis()));
        arguments.add(Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION));
        fields(arguments,
                "scope", scope.name(),
                "authState", "ANONYMOUS",
                "sessionType", RiskSessionType.NONE.name(),
                "sessionRefDigest", "",
                "deviceDigest", deviceDigest.value(),
                "lastSeenAt", snapshot.observedAt().toString());
        currentFields(arguments, snapshot);
        trustedFields(arguments, trustCurrent ? snapshot : null);
        fields(arguments,
                "lastDecision", decision.name(),
                "lastDecisionAt", snapshot.observedAt().toString(),
                "lastDecisionContextDigest", protectedText(contextDigest),
                "temporaryBlockUntil", instant(temporaryBlockUntil),
                "challengeVerifiedUntil", "",
                "impossibleTravelCount", "0",
                "impossibleTravelEvents", "[]",
                "challengeIssuedCount", "0",
                "challengePassedCount", "0",
                "activeChallengeNonce", "",
                "activeChallengeIpDigest", "",
                "activeChallengeContextDigest", "",
                "activeChallengeExpiresAt", "");
        Long result = redisTemplate.execute(
                CREATE,
                List.of(key(scope, tokenDigest)),
                arguments.toArray());
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public Optional<PreAuthState> find(
            RiskScope scope,
            HmacIdentifier tokenDigest) {
        String redisKey = key(scope, tokenDigest);
        Map<Object, Object> values =
                redisTemplate.opsForHash().entries(redisKey);
        if (values.isEmpty()) {
            return Optional.empty();
        }
        try {
            if (!scope.name().equals(value(values, "scope"))) {
                return Optional.empty();
            }
            return Optional.of(new PreAuthState(
                    Integer.parseInt(value(values, "schemaVersion")),
                    scope,
                    value(values, "authState"),
                    RiskSessionType.valueOf(value(values, "sessionType")),
                    protectedValue(values, "sessionRefDigest"),
                    requiredProtectedValue(values, "deviceDigest"),
                    Instant.parse(value(values, "lastSeenAt")),
                    requiredProtectedValue(values, "currentIpDigest"),
                    Integer.parseInt(value(values, "currentTrustScore")),
                    nullable(values, "currentCountryCode"),
                    nullableLong(values, "currentAsn"),
                    nullableDecimal(values, "currentLatitude"),
                    nullableDecimal(values, "currentLongitude"),
                    NetworkType.valueOf(value(values, "currentNetworkType")),
                    Boolean.parseBoolean(value(
                            values,
                            "currentScoreIncludesNetworkRisk")),
                    PreAuthRiskSource.valueOf(value(values, "currentRiskSource")),
                    PreAuthGeoSource.valueOf(value(values, "currentGeoSource")),
                    protectedValue(values, "lastTrustedIpDigest"),
                    nullable(values, "lastTrustedCountryCode"),
                    nullableLong(values, "lastTrustedAsn"),
                    nullableDecimal(values, "lastTrustedLatitude"),
                    nullableDecimal(values, "lastTrustedLongitude"),
                    nullableInstant(values, "lastTrustedObservedAt"),
                    RiskDecision.valueOf(value(values, "lastDecision")),
                    Instant.parse(value(values, "lastDecisionAt")),
                    protectedValue(values, "lastDecisionContextDigest"),
                    nullableInstant(values, "temporaryBlockUntil"),
                    nullableInstant(values, "challengeVerifiedUntil"),
                    Long.parseLong(value(values, "impossibleTravelCount")),
                    value(values, "impossibleTravelEvents"),
                    Long.parseLong(value(values, "challengeIssuedCount")),
                    Long.parseLong(value(values, "challengePassedCount")),
                    nullable(values, "activeChallengeNonce"),
                    protectedValue(values, "activeChallengeIpDigest"),
                    protectedValue(values, "activeChallengeContextDigest"),
                    nullableInstant(values, "activeChallengeExpiresAt"),
                    PreAuthWebRtcPhase.valueOf(value(values, "webRtcPhase")),
                    Long.parseLong(value(values, "webRtcGeneration")),
                    nullableEpochMillis(values, "webRtcDeadlineAt"),
                    nullableEnum(
                            values,
                            "webRtcFailureReason",
                            PreAuthWebRtcFailureReason.class),
                    nullable(values, "webRtcIps")));
        } catch (RuntimeException exception) {
            // 无法解析的状态不能参与安全决策；异步释放损坏 Key，避免主线程被大 Key 删除阻塞。
            redisTemplate.unlink(redisKey);
            return Optional.empty();
        }
    }

    @Override
    public boolean touch(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            Instant seenAt,
            Duration ttl) {
        return mutate(
                scope,
                tokenDigest,
                deviceDigest,
                ttl,
                "lastSeenAt", seenAt.toString());
    }

    @Override
    public boolean recordAssessment(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            Instant decisionAt,
            HmacIdentifier contextDigest,
            Instant temporaryBlockUntil,
            boolean trustCurrent,
            Duration startGrace,
            Duration ttl) {
        List<String> fieldValues = new ArrayList<>();
        fields(fieldValues, "lastSeenAt", decisionAt.toString());
        currentFields(fieldValues, snapshot);
        if (trustCurrent) {
            trustedFields(fieldValues, snapshot);
        }
        fields(fieldValues,
                "lastDecision", decision.name(),
                "lastDecisionAt", decisionAt.toString(),
                "lastDecisionContextDigest", protectedText(contextDigest),
                "temporaryBlockUntil", instant(temporaryBlockUntil));
        if (decision != RiskDecision.CHALLENGE) {
            clearActiveChallenge(fieldValues);
        }
        List<String> arguments = new ArrayList<>(6 + fieldValues.size());
        arguments.add(Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION));
        arguments.add(deviceDigest.value());
        arguments.add(scope.name());
        arguments.add(snapshot.ipDigest().value());
        // IP 变化时只创建 REQUIRED；真正的 report 窗口由后续 begin 使用 Redis TIME 开启。
        arguments.add(Long.toString(startGrace.toMillis()));
        arguments.add(Long.toString(ttl.toMillis()));
        arguments.addAll(fieldValues);
        Long result = redisTemplate.execute(
                RECORD_ASSESSMENT,
                List.of(key(scope, tokenDigest)),
                arguments.toArray());
        return Long.valueOf(1L).equals(result);
    }

    @Override
    @SuppressWarnings("unchecked")
    public PreAuthWebRtcBeginResult beginWebRtcVerification(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long expectedGeneration,
            Duration verificationWindow,
            Duration ttl) {
        if (expectedGeneration <= 0
                || verificationWindow == null
                || verificationWindow.isZero()
                || verificationWindow.isNegative()) {
            throw new IllegalArgumentException("WebRTC begin parameters are invalid.");
        }
        List<Object> result = redisTemplate.execute(
                BEGIN_WEBRTC,
                List.of(key(scope, tokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                scope.name(),
                currentIpDigest.value(),
                Long.toString(expectedGeneration),
                Long.toString(verificationWindow.toMillis()),
                Long.toString(ttl.toMillis()));
        if (result == null || result.size() < 4) {
            return new PreAuthWebRtcBeginResult(
                    PreAuthWebRtcBeginResult.Status.STATE_INVALID,
                    0L,
                    null,
                    0L);
        }
        long statusCode = Long.parseLong(Objects.toString(result.get(0)));
        long generation = Long.parseLong(Objects.toString(result.get(1)));
        long deadlineMillis = Long.parseLong(Objects.toString(result.get(2)));
        long remainingMillis = Long.parseLong(Objects.toString(result.get(3)));
        PreAuthWebRtcBeginResult.Status status = switch ((int) statusCode) {
            case 1 -> PreAuthWebRtcBeginResult.Status.STARTED;
            case 2 -> PreAuthWebRtcBeginResult.Status.VERIFIED_PRESERVED;
            case 3 -> PreAuthWebRtcBeginResult.Status.FAILURE_PRESERVED;
            case 4 -> PreAuthWebRtcBeginResult.Status.START_TIMEOUT;
            case 5 -> PreAuthWebRtcBeginResult.Status.PENDING_PRESERVED;
            case 6 -> PreAuthWebRtcBeginResult.Status.REPORT_TIMEOUT;
            case -1 -> PreAuthWebRtcBeginResult.Status.NETWORK_CHANGED;
            case -2 -> PreAuthWebRtcBeginResult.Status.STALE_GENERATION;
            default -> PreAuthWebRtcBeginResult.Status.STATE_INVALID;
        };
        Instant deadline = deadlineMillis > 0
                ? Instant.ofEpochMilli(deadlineMillis)
                : null;
        return new PreAuthWebRtcBeginResult(
                status,
                Math.max(0L, generation),
                deadline,
                Math.max(0L, remainingMillis));
    }

    @Override
    public PreAuthWebRtcWriteResult writeWebRtcResult(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long probeGeneration,
            boolean verified,
            PreAuthWebRtcFailureReason failureReason,
            String encryptedWebRtcIps,
            boolean hasReportedIps,
            Duration ttl) {
        if (probeGeneration <= 0) {
            throw new IllegalArgumentException("WebRTC generation is required.");
        }
        if (hasReportedIps
                && (encryptedWebRtcIps == null || encryptedWebRtcIps.isBlank())) {
            throw new IllegalArgumentException("Encrypted WebRTC IPs are required.");
        }
        if (!hasReportedIps && encryptedWebRtcIps != null) {
            throw new IllegalArgumentException("Empty WebRTC reports cannot retain evidence.");
        }
        if (verified == (failureReason != null)) {
            throw new IllegalArgumentException("WebRTC result and failure reason are inconsistent.");
        }
        Long result = redisTemplate.execute(
                WRITE_WEBRTC,
                List.of(key(scope, tokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                scope.name(),
                currentIpDigest.value(),
                Long.toString(probeGeneration),
                verified ? PreAuthWebRtcPhase.VERIFIED.name() : PreAuthWebRtcPhase.FAILED.name(),
                failureReason == null ? "" : failureReason.name(),
                encryptedWebRtcIps == null ? "" : encryptedWebRtcIps,
                hasReportedIps ? "1" : "0",
                Long.toString(ttl.toMillis()));
        if (Long.valueOf(1L).equals(result)) {
            return PreAuthWebRtcWriteResult.UPDATED;
        }
        if (Long.valueOf(2L).equals(result)) {
            return PreAuthWebRtcWriteResult.VERIFIED_PRESERVED;
        }
        if (Long.valueOf(3L).equals(result)) {
            return PreAuthWebRtcWriteResult.FAILURE_PRESERVED;
        }
        if (Long.valueOf(4L).equals(result)) {
            return PreAuthWebRtcWriteResult.DEADLINE_EXPIRED;
        }
        if (Long.valueOf(-2L).equals(result)) {
            return PreAuthWebRtcWriteResult.STALE_GENERATION;
        }
        if (Long.valueOf(-1L).equals(result)) {
            return PreAuthWebRtcWriteResult.NETWORK_CHANGED;
        }
        return PreAuthWebRtcWriteResult.PREAUTH_UNAVAILABLE;
    }

    @Override
    public boolean expireWebRtcDeadline(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            long probeGeneration,
            Duration ttl) {
        Long result = redisTemplate.execute(
                EXPIRE_WEBRTC,
                List.of(key(scope, tokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                scope.name(),
                currentIpDigest.value(),
                Long.toString(probeGeneration),
                Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public long recordImpossibleTravelEvent(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier eventDigest,
            Instant occurredAt,
            Duration eventWindow,
            int maximumEvents,
            Duration ttl) {
        Long result = redisTemplate.execute(
                RECORD_EVENT,
                List.of(key(scope, tokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                Long.toString(occurredAt.toEpochMilli()),
                Long.toString(eventWindow.toMillis()),
                eventDigest == null ? "" : eventDigest.value(),
                Integer.toString(maximumEvents),
                Long.toString(ttl.toMillis()));
        return result == null || result < 0L ? 0L : result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<PreAuthChallengeActivation> activateChallenge(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String proposedNonce,
            Instant now,
            Instant expiresAt,
            Duration ttl) {
        List<Object> result = redisTemplate.execute(
                ACTIVATE_CHALLENGE,
                List.of(key(scope, tokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                currentIpDigest.value(),
                contextDigest.value(),
                proposedNonce,
                luaComparableInstant(now),
                luaComparableInstant(expiresAt),
                Long.toString(ttl.toMillis()));
        if (result == null || result.size() < 3) {
            return Optional.empty();
        }
        long status = Long.parseLong(Objects.toString(result.get(0)));
        if (status < 0L) {
            return Optional.empty();
        }
        return Optional.of(new PreAuthChallengeActivation(
                Objects.toString(result.get(1)),
                Instant.parse(Objects.toString(result.get(2))),
                status == 1L));
    }

    @Override
    public boolean consumeChallengeAndTrust(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String nonce,
            Instant now,
            Instant challengeVerifiedUntil,
            Duration ttl) {
        Long result = redisTemplate.execute(
                CONSUME_CHALLENGE,
                List.of(key(scope, tokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                currentIpDigest.value(),
                contextDigest.value(),
                nonce,
                luaComparableInstant(now),
                challengeVerifiedUntil.toString(),
                Long.toString(ttl.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public boolean rotateAuthenticated(
            RiskScope scope,
            HmacIdentifier oldTokenDigest,
            HmacIdentifier newTokenDigest,
            HmacIdentifier deviceDigest,
            RiskSessionType sessionType,
            HmacIdentifier sessionRefDigest,
            HmacIdentifier decisionContextDigest,
            PreAuthWebRtcPhase expectedSourceWebRtcPhase,
            long expectedSourceWebRtcGeneration,
            PreAuthWebRtcPhase webRtcPhase,
            long webRtcGeneration,
            String encryptedWebRtcIps,
            Instant seenAt,
            Duration startGrace,
            Duration ttl) {
        if (expectedSourceWebRtcPhase == null
                || expectedSourceWebRtcGeneration <= 0
                || webRtcPhase == null
                || webRtcGeneration <= 0) {
            throw new IllegalArgumentException("Rotated WebRTC state is required.");
        }
        if (webRtcPhase != PreAuthWebRtcPhase.REQUIRED
                && webRtcPhase != PreAuthWebRtcPhase.VERIFIED) {
            throw new IllegalArgumentException(
                    "Only REQUIRED or VERIFIED may survive token rotation.");
        }
        if (webRtcPhase == PreAuthWebRtcPhase.VERIFIED
                && (encryptedWebRtcIps == null || encryptedWebRtcIps.isBlank())) {
            throw new IllegalArgumentException(
                    "Rotated WebRTC state requires encrypted IPs.");
        }
        if (webRtcPhase == PreAuthWebRtcPhase.REQUIRED
                && encryptedWebRtcIps != null) {
            throw new IllegalArgumentException(
                    "Rotated REQUIRED state must not contain encrypted IPs.");
        }
        Long result = redisTemplate.execute(
                ROTATE_AUTHENTICATED,
                List.of(
                        key(scope, oldTokenDigest),
                        key(scope, newTokenDigest)),
                Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION),
                deviceDigest.value(),
                Long.toString(ttl.toMillis()),
                seenAt.toString(),
                sessionType.name(),
                sessionRefDigest.value(),
                decisionContextDigest.value(),
                expectedSourceWebRtcPhase.name(),
                Long.toString(expectedSourceWebRtcGeneration),
                webRtcPhase.name(),
                Long.toString(webRtcGeneration),
                encryptedWebRtcIps == null ? "" : encryptedWebRtcIps,
                Long.toString(startGrace.toMillis()));
        return Long.valueOf(1L).equals(result);
    }

    @Override
    public void delete(RiskScope scope, HmacIdentifier tokenDigest) {
        redisTemplate.unlink(key(scope, tokenDigest));
    }

    private boolean mutate(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            Duration ttl,
            String... fieldValues) {
        List<String> arguments = new ArrayList<>(3 + fieldValues.length);
        arguments.add(Integer.toString(PreAuthState.CURRENT_SCHEMA_VERSION));
        arguments.add(deviceDigest.value());
        arguments.add(Long.toString(ttl.toMillis()));
        fields(arguments, fieldValues);
        Long result = redisTemplate.execute(
                MUTATE,
                List.of(key(scope, tokenDigest)),
                arguments.toArray());
        return Long.valueOf(1L).equals(result);
    }

    private String key(RiskScope scope, HmacIdentifier digest) {
        return scope == RiskScope.ADMIN
                ? keyFactory.adminPreAuthKey(digest)
                : keyFactory.userPreAuthKey(digest);
    }

    private static void currentFields(
            List<String> target,
            PreAuthNetworkSnapshot snapshot) {
        fields(target,
                "currentIpDigest", snapshot.ipDigest().value(),
                "currentTrustScore", Integer.toString(snapshot.trustScore()),
                "currentCountryCode", text(snapshot.countryCode()),
                "currentAsn", text(snapshot.asn()),
                "currentLatitude", text(snapshot.latitude()),
                "currentLongitude", text(snapshot.longitude()),
                "currentNetworkType", snapshot.networkType().name(),
                "currentScoreIncludesNetworkRisk",
                Boolean.toString(snapshot.scoreIncludesNetworkRisk()),
                "currentRiskSource", snapshot.riskSource().name(),
                "currentGeoSource", snapshot.geoSource().name());
    }

    private static void trustedFields(
            List<String> target,
            PreAuthNetworkSnapshot snapshot) {
        fields(target,
                "lastTrustedIpDigest",
                snapshot == null ? "" : snapshot.ipDigest().value(),
                "lastTrustedCountryCode",
                snapshot == null ? "" : text(snapshot.countryCode()),
                "lastTrustedAsn",
                snapshot == null ? "" : text(snapshot.asn()),
                "lastTrustedLatitude",
                snapshot == null ? "" : text(snapshot.latitude()),
                "lastTrustedLongitude",
                snapshot == null ? "" : text(snapshot.longitude()),
                "lastTrustedObservedAt",
                snapshot == null ? "" : snapshot.observedAt().toString());
    }

    private static void clearActiveChallenge(List<String> target) {
        fields(target,
                "activeChallengeNonce", "",
                "activeChallengeIpDigest", "",
                "activeChallengeContextDigest", "",
                "activeChallengeExpiresAt", "");
    }

    private static void fields(List<String> target, String... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Redis Hash fields must be name/value pairs.");
        }
        target.addAll(List.of(values));
    }

    private static String value(Map<Object, Object> values, String field) {
        String value = Objects.toString(values.get(field), "");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("PreAuth field is missing.");
        }
        return value;
    }

    private static String nullable(Map<Object, Object> values, String field) {
        String value = Objects.toString(values.get(field), "");
        return value.isEmpty() ? null : value;
    }

    private static HmacIdentifier requiredProtectedValue(
            Map<Object, Object> values,
            String field) {
        return HmacIdentifier.fromProtectedValue(value(values, field));
    }

    private static HmacIdentifier protectedValue(
            Map<Object, Object> values,
            String field) {
        String value = nullable(values, field);
        return value == null
                ? null
                : HmacIdentifier.fromProtectedValue(value);
    }

    private static Long nullableLong(Map<Object, Object> values, String field) {
        String value = nullable(values, field);
        return value == null ? null : Long.valueOf(value);
    }

    private static java.math.BigDecimal nullableDecimal(
            Map<Object, Object> values,
            String field) {
        String value = nullable(values, field);
        return value == null ? null : new java.math.BigDecimal(value);
    }

    private static Instant nullableInstant(
            Map<Object, Object> values,
            String field) {
        String value = nullable(values, field);
        return value == null ? null : Instant.parse(value);
    }

    private static Instant nullableEpochMillis(
            Map<Object, Object> values,
            String field) {
        String value = nullable(values, field);
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    private static <E extends Enum<E>> E nullableEnum(
            Map<Object, Object> values,
            String field,
            Class<E> type) {
        String value = nullable(values, field);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static String protectedText(HmacIdentifier value) {
        return value == null ? "" : value.value();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }

    private static String luaComparableInstant(Instant value) {
        // Lua 不提供 Instant 解析器；固定九位小数和 UTC 后，字符串顺序与时间顺序严格一致。
        return LUA_COMPARABLE_INSTANT.format(value);
    }

    private static RedisScript<Long> longScript(String path) {
        return new DefaultRedisScript<>(source(path), Long.class);
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> listScript(String path) {
        return new DefaultRedisScript<>(source(path), List.class);
    }

    private static String source(String path) {
        try {
            return StreamUtils.copyToString(
                    new ClassPathResource(path).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
