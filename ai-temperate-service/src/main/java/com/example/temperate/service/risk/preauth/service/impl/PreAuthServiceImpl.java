package com.example.temperate.service.risk.preauth.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskDecision;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthChallengeActivation;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthNetworkSnapshot;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtectionException;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtector;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 使用 256 位随机 Token 和单个 Redis Hash 实现普通与管理员隔离的 PreAuth v4 生命周期。
 *
 * <p>IP 信用快照、事件计数和活动 Challenge 都通过 Store 的 Lua 边界写入同一个 Hash；原始
 * PreAuth Token、明文 IP 和原始会话 Token 从不写入 Redis、日志或异常。</p>
 */
@Service
public final class PreAuthServiceImpl implements PreAuthService {

    private static final int TOKEN_BYTES = 32;
    private static final int CREATE_ATTEMPTS = 4;
    private static final Duration IMPOSSIBLE_TRAVEL_EVENT_WINDOW =
            Duration.ofMinutes(30);
    private static final int MAXIMUM_IMPOSSIBLE_TRAVEL_EVENTS = 100;

    private final PreAuthStore store;
    private final NetworkRiskIdentifier identifier;
    private final NetworkRiskProperties properties;
    private final WebRtcIpProtector webRtcIpProtector;
    private final SecureRandom secureRandom = new SecureRandom();

    public PreAuthServiceImpl(
            PreAuthStore store,
            NetworkRiskIdentifier identifier,
            NetworkRiskProperties properties,
            WebRtcIpProtector webRtcIpProtector) {
        this.store = Objects.requireNonNull(store);
        this.identifier = Objects.requireNonNull(identifier);
        this.properties = Objects.requireNonNull(properties);
        this.webRtcIpProtector = Objects.requireNonNull(webRtcIpProtector);
    }

    @Override
    public PreAuthIssue createEvaluated(
            RiskScope scope,
            String rawDeviceId,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            Instant temporaryBlockUntil,
            boolean trustCurrent) {
        HmacIdentifier deviceDigest =
                identifier.identifyDevice(normalizeDevice(rawDeviceId));
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            String rawToken = randomToken();
            HmacIdentifier tokenDigest = identifier.identifyPreAuthToken(rawToken);
            HmacIdentifier contextDigest = identifier.identifyDecisionContext(
                    scope.name()
                            + "|"
                            + tokenDigest.value()
                            + "|"
                            + deviceDigest.value()
                            + "|"
                            + snapshot.ipDigest().value());
            if (store.create(
                    scope,
                    tokenDigest,
                    deviceDigest,
                    snapshot,
                    decision,
                    contextDigest,
                    temporaryBlockUntil,
                    trustCurrent,
                    properties.anonymousPreAuthTtl())) {
                return new PreAuthIssue(
                        rawToken,
                        snapshot.observedAt().plus(properties.anonymousPreAuthTtl()));
            }
        }
        throw new IllegalStateException("PreAuth token allocation failed.");
    }

    @Override
    public Optional<PreAuthAccess> resolve(
            RiskScope scope,
            String rawToken,
            String rawDeviceId) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        try {
            HmacIdentifier tokenDigest = identifier.identifyPreAuthToken(rawToken);
            HmacIdentifier deviceDigest =
                    identifier.identifyDevice(normalizeDevice(rawDeviceId));
            return store.find(scope, tokenDigest)
                    .filter(state -> state.schemaVersion()
                            == PreAuthState.CURRENT_SCHEMA_VERSION)
                    .filter(state -> state.scope() == scope)
                    .filter(state -> state.deviceDigest().equals(deviceDigest))
                    .map(state -> new PreAuthAccess(tokenDigest, state));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<PreAuthAccess> resolveChallengeNavigation(
            RiskScope scope,
            String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        try {
            HmacIdentifier tokenDigest = identifier.identifyPreAuthToken(rawToken);
            return store.find(scope, tokenDigest)
                    .filter(state -> state.schemaVersion()
                            == PreAuthState.CURRENT_SCHEMA_VERSION)
                    .filter(state -> state.scope() == scope)
                    .map(state -> new PreAuthAccess(tokenDigest, state));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    @Override
    public boolean touch(PreAuthAccess access, Instant seenAt) {
        return store.touch(
                access.state().scope(),
                access.tokenDigest(),
                access.state().deviceDigest(),
                seenAt,
                ttl(access.state()));
    }

    @Override
    public PreAuthSessionBinding requireSessionBinding(
            PreAuthAccess access,
            RiskScope expectedScope,
            RiskSessionType expectedSessionType,
            String rawSessionReference) {
        HmacIdentifier expectedSessionDigest =
                rawSessionReference == null || rawSessionReference.isBlank()
                        ? null
                        : identifier.identifySession(rawSessionReference);
        boolean anonymousRecovery = access != null
                && "ANONYMOUS".equals(access.state().authState())
                && access.state().sessionType() == RiskSessionType.NONE
                && access.state().sessionRefDigest() == null;
        boolean alreadyBound = access != null
                && access.state().sessionType() == expectedSessionType
                && access.state().sessionRefDigest() != null
                && access.state().sessionRefDigest().equals(expectedSessionDigest);
        if (access == null
                || expectedScope == null
                || expectedSessionType == null
                || access.state().scope() != expectedScope
                || expectedSessionDigest == null
                || (!anonymousRecovery && !alreadyBound)) {
            throw new IllegalArgumentException(
                    "Authenticated PreAuth binding is invalid.");
        }
        return new PreAuthSessionBinding(
                expectedScope,
                access.tokenDigest(),
                access.state().deviceDigest(),
                expectedSessionType,
                expectedSessionDigest,
                properties.authenticatedPreAuthTtl(),
                anonymousRecovery);
    }

    @Override
    public boolean recordAssessment(
            PreAuthAccess access,
            PreAuthNetworkSnapshot snapshot,
            RiskDecision decision,
            Instant decisionAt,
            HmacIdentifier contextDigest,
            Instant temporaryBlockUntil,
            boolean trustCurrent) {
        return store.recordAssessment(
                access.state().scope(),
                access.tokenDigest(),
                access.state().deviceDigest(),
                snapshot,
                decision,
                decisionAt,
                contextDigest,
                temporaryBlockUntil,
                trustCurrent,
                ttl(access.state()));
    }

    @Override
    public long recordImpossibleTravelEvent(
            PreAuthAccess access,
            HmacIdentifier eventDigest,
            Instant occurredAt) {
        return store.recordImpossibleTravelEvent(
                access.state().scope(),
                access.tokenDigest(),
                access.state().deviceDigest(),
                eventDigest,
                occurredAt,
                IMPOSSIBLE_TRAVEL_EVENT_WINDOW,
                MAXIMUM_IMPOSSIBLE_TRAVEL_EVENTS,
                ttl(access.state()));
    }

    @Override
    public Optional<PreAuthChallengeActivation> activateChallenge(
            PreAuthAccess access,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String proposedNonce,
            Instant now) {
        return store.activateChallenge(
                access.state().scope(),
                access.tokenDigest(),
                access.state().deviceDigest(),
                currentIpDigest,
                contextDigest,
                proposedNonce,
                now,
                now.plus(properties.challengeTtl()),
                ttl(access.state()));
    }

    @Override
    public boolean consumeChallengeAndTrust(
            PreAuthAccess access,
            HmacIdentifier currentIpDigest,
            HmacIdentifier contextDigest,
            String nonce,
            Instant now) {
        return store.consumeChallengeAndTrust(
                access.state().scope(),
                access.tokenDigest(),
                access.state().deviceDigest(),
                currentIpDigest,
                contextDigest,
                nonce,
                now,
                now.plus(properties.challengeVerifiedTtl()),
                ttl(access.state()));
    }

    @Override
    public PreAuthIssue promoteAuthenticated(
            PreAuthAccess access,
            RiskSessionType sessionType,
            String rawSessionReference,
            Instant seenAt) {
        PreAuthAccess oldAccess = Objects.requireNonNull(
                access, "verified PreAuth access must not be null");
        RiskScope scope = Objects.requireNonNull(
                oldAccess.state(), "verified PreAuth state must not be null").scope();
        if (sessionType == null
                || sessionType == RiskSessionType.NONE
                || rawSessionReference == null
                || rawSessionReference.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated session reference is required.");
        }
        HmacIdentifier sessionDigest =
                identifier.identifySession(rawSessionReference);
        WebRtcRotationState webRtcState = decryptWebRtcForRotation(oldAccess);
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            String newRawToken = randomToken();
            HmacIdentifier newTokenDigest =
                    identifier.identifyPreAuthToken(newRawToken);
            HmacIdentifier newContextDigest =
                    identifier.identifyDecisionContext(
                            scope.name()
                                    + "|"
                                    + newTokenDigest.value()
                                    + "|"
                                    + oldAccess.state().deviceDigest().value()
                                    + "|"
                                    + oldAccess.state().currentIpDigest().value());
            String rotatedWebRtcIps = webRtcState.status() == null
                    ? null
                    : webRtcIpProtector.encrypt(
                            webRtcState.ips(),
                            scope,
                            newTokenDigest,
                            oldAccess.state().currentIpDigest());
            if (store.rotateAuthenticated(
                    scope,
                    oldAccess.tokenDigest(),
                    newTokenDigest,
                    oldAccess.state().deviceDigest(),
                    sessionType,
                    sessionDigest,
                    newContextDigest,
                    webRtcState.status(),
                    rotatedWebRtcIps,
                    seenAt,
                    properties.authenticatedPreAuthTtl())) {
                return new PreAuthIssue(
                        newRawToken,
                        seenAt.plus(properties.authenticatedPreAuthTtl()));
            }
        }
        throw new IllegalStateException("Authenticated PreAuth rotation failed.");
    }

    private WebRtcRotationState decryptWebRtcForRotation(PreAuthAccess oldAccess) {
        if (oldAccess.state().webRtcStatus() == null) {
            return new WebRtcRotationState(null, List.of());
        }
        try {
            List<String> ips = webRtcIpProtector.decrypt(
                    oldAccess.state().webRtcIps(),
                    oldAccess.state().scope(),
                    oldAccess.tokenDigest(),
                    oldAccess.state().currentIpDigest());
            return new WebRtcRotationState(
                    oldAccess.state().webRtcStatus(),
                    ips);
        } catch (WebRtcIpProtectionException exception) {
            // 损坏密文不能迁移到新 Token；登录本身继续，后续请求会回到未校验状态重新探测。
            return new WebRtcRotationState(null, List.of());
        }
    }

    @Override
    public void revoke(RiskScope scope, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        store.delete(scope, identifier.identifyPreAuthToken(rawToken));
    }

    private Duration ttl(PreAuthState state) {
        return state.authenticated()
                ? properties.authenticatedPreAuthTtl()
                : properties.anonymousPreAuthTtl();
    }

    /**
     * 暂存登录旋转前已认证解密的 WebRTC 状态，不跨请求、不写日志也不离开当前方法调用链。
     */
    private record WebRtcRotationState(Boolean status, List<String> ips) {

        private WebRtcRotationState {
            ips = List.copyOf(ips);
        }
    }

    private String randomToken() {
        byte[] random = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static String normalizeDevice(String rawDeviceId) {
        if (rawDeviceId == null) {
            throw new IllegalArgumentException("Device installation ID is required.");
        }
        String value = rawDeviceId.trim();
        if (value.length() < 8
                || value.length() > 200
                || !value.matches("^[A-Za-z0-9._:-]+$")) {
            throw new IllegalArgumentException(
                    "Device installation ID is invalid.");
        }
        return value;
    }
}
