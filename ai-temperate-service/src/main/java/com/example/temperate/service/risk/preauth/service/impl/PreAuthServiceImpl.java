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
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.domain.PreAuthSessionBinding;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
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
 * 使用 256 位随机 Token 和单个 Redis Hash 实现普通与管理员隔离的 PreAuth v7 生命周期。
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
                    properties.webRtc().startGrace(),
                    properties.anonymousPreAuthTtl())) {
                return new PreAuthIssue(
                        rawToken,
                        snapshot.observedAt().plus(properties.anonymousPreAuthTtl()),
                        PreAuthWebRtcPhase.REQUIRED,
                        1L);
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
    public Optional<PreAuthAccess> resolveBound(
            RiskScope scope,
            HmacIdentifier tokenDigest,
            HmacIdentifier deviceDigest,
            RiskSessionType sessionType,
            HmacIdentifier sessionReferenceDigest) {
        if (scope == null || tokenDigest == null || deviceDigest == null
                || sessionType == null || sessionType == RiskSessionType.NONE
                || sessionReferenceDigest == null) {
            return Optional.empty();
        }
        // 握手只持有受保护摘要；所有绑定字段必须来自同一份当前 Redis 状态，禁止信任 Ticket 快照本身。
        return store.find(scope, tokenDigest)
                .filter(state -> state.schemaVersion() == PreAuthState.CURRENT_SCHEMA_VERSION)
                .filter(state -> state.scope() == scope)
                .filter(state -> state.deviceDigest().equals(deviceDigest))
                .filter(state -> state.sessionType() == sessionType)
                .filter(state -> sessionReferenceDigest.equals(state.sessionRefDigest()))
                .map(state -> new PreAuthAccess(tokenDigest, state));
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
                properties.webRtc().startGrace(),
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
        PreAuthAccess rotationAccess = oldAccess;
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            WebRtcRotationState webRtcState = decryptWebRtcForRotation(rotationAccess);
            String newRawToken = randomToken();
            HmacIdentifier newTokenDigest =
                    identifier.identifyPreAuthToken(newRawToken);
            HmacIdentifier newContextDigest =
                    identifier.identifyDecisionContext(
                            scope.name()
                                    + "|"
                                    + newTokenDigest.value()
                                    + "|"
                                    + rotationAccess.state().deviceDigest().value()
                                    + "|"
                                    + rotationAccess.state().currentIpDigest().value());
            String rotatedWebRtcIps = webRtcState.ips().isEmpty()
                    ? null
                    : webRtcIpProtector.encrypt(
                            webRtcState.ips(),
                            scope,
                            newTokenDigest,
                            rotationAccess.state().currentIpDigest());
            if (store.rotateAuthenticated(
                    scope,
                    oldAccess.tokenDigest(),
                    newTokenDigest,
                    rotationAccess.state().deviceDigest(),
                    sessionType,
                    sessionDigest,
                    newContextDigest,
                    rotationAccess.state().webRtcPhase(),
                    rotationAccess.state().webRtcGeneration(),
                    webRtcState.phase(),
                    webRtcState.generation(),
                    rotatedWebRtcIps,
                    seenAt,
                    properties.webRtc().startGrace(),
                    properties.authenticatedPreAuthTtl())) {
                return new PreAuthIssue(
                        newRawToken,
                        seenAt.plus(properties.authenticatedPreAuthTtl()),
                        webRtcState.phase(),
                        webRtcState.generation());
            }
            // 源 generation 在登录并发窗口内可能被 IP 变化推进；失败后只重读原 Token，再按新状态重算轮换。
            Optional<PreAuthState> refreshed = store.find(
                    scope,
                    oldAccess.tokenDigest());
            if (refreshed.isPresent()) {
                rotationAccess = new PreAuthAccess(
                        oldAccess.tokenDigest(),
                        refreshed.get());
            }
        }
        throw new IllegalStateException("Authenticated PreAuth rotation failed.");
    }

    @Override
    public PreAuthIssue promoteAuthenticatedAfterWebRtcVerified(
            PreAuthAccess access,
            RiskSessionType sessionType,
            String rawSessionReference,
            String currentHttpIp,
            Instant seenAt) {
        if (access == null || access.state() == null
                || currentHttpIp == null || currentHttpIp.isBlank()) {
            throw new PreAuthRequiredException();
        }
        if (sessionType == null
                || sessionType == RiskSessionType.NONE
                || rawSessionReference == null
                || rawSessionReference.isBlank()) {
            throw new IllegalArgumentException(
                    "Authenticated session reference is required.");
        }
        HmacIdentifier currentIpDigest;
        try {
            currentIpDigest = identifier.identifyIp(currentHttpIp);
        } catch (IllegalArgumentException exception) {
            throw new PreAuthRequiredException();
        }
        RiskScope scope = access.state().scope();
        HmacIdentifier sessionDigest = identifier.identifySession(rawSessionReference);
        for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
            // OAuth complete 可能与 report 并发；每次尝试都必须重读旧 Token，并锁定进入请求时的 generation。
            Optional<PreAuthState> freshState = store.find(scope, access.tokenDigest())
                    .filter(state -> state.schemaVersion()
                            == PreAuthState.CURRENT_SCHEMA_VERSION)
                    .filter(state -> state.scope() == scope)
                    .filter(state -> state.deviceDigest().equals(
                            access.state().deviceDigest()))
                    .filter(state -> state.currentIpDigest().equals(currentIpDigest))
                    .filter(state -> state.webRtcPhase()
                            == PreAuthWebRtcPhase.VERIFIED)
                    .filter(state -> state.webRtcGeneration()
                            == access.state().webRtcGeneration());
            if (freshState.isEmpty()) {
                throw new PreAuthRequiredException();
            }
            PreAuthState verifiedState = freshState.get();
            List<String> verifiedIps;
            try {
                verifiedIps = webRtcIpProtector.decrypt(
                        verifiedState.webRtcIps(),
                        scope,
                        access.tokenDigest(),
                        currentIpDigest);
            } catch (WebRtcIpProtectionException exception) {
                throw new PreAuthRequiredException();
            }
            if (verifiedIps.isEmpty()) {
                throw new PreAuthRequiredException();
            }

            String newRawToken = randomToken();
            HmacIdentifier newTokenDigest =
                    identifier.identifyPreAuthToken(newRawToken);
            HmacIdentifier newContextDigest =
                    identifier.identifyDecisionContext(
                            scope.name()
                                    + "|"
                                    + newTokenDigest.value()
                                    + "|"
                                    + verifiedState.deviceDigest().value()
                                    + "|"
                                    + currentIpDigest.value());
            String rotatedWebRtcIps;
            try {
                rotatedWebRtcIps = webRtcIpProtector.encrypt(
                        verifiedIps,
                        scope,
                        newTokenDigest,
                        currentIpDigest);
            } catch (WebRtcIpProtectionException exception) {
                throw new PreAuthRequiredException();
            }
            // 专用 Lua 原子核对源 phase/generation/IP/决策上下文；失败时绝不生成 REQUIRED 下一代。
            if (store.rotateAuthenticatedAfterWebRtcVerified(
                    scope,
                    access.tokenDigest(),
                    newTokenDigest,
                    verifiedState.deviceDigest(),
                    currentIpDigest,
                    verifiedState.lastDecisionContextDigest(),
                    sessionType,
                    sessionDigest,
                    newContextDigest,
                    verifiedState.webRtcGeneration(),
                    rotatedWebRtcIps,
                    seenAt,
                    properties.authenticatedPreAuthTtl())) {
                return new PreAuthIssue(
                        newRawToken,
                        seenAt.plus(properties.authenticatedPreAuthTtl()),
                        PreAuthWebRtcPhase.VERIFIED,
                        verifiedState.webRtcGeneration());
            }
        }
        throw new PreAuthRequiredException();
    }

    private WebRtcRotationState decryptWebRtcForRotation(PreAuthAccess oldAccess) {
        if (oldAccess.state().webRtcPhase() != PreAuthWebRtcPhase.VERIFIED) {
            // Token 轮换会改变 report 的绑定身份；未完成或失败状态必须建立新 generation，不能继承旧窗口。
            return new WebRtcRotationState(
                    PreAuthWebRtcPhase.REQUIRED,
                    nextGeneration(oldAccess.state().webRtcGeneration()),
                    List.of());
        }
        try {
            List<String> ips = webRtcIpProtector.decrypt(
                    oldAccess.state().webRtcIps(),
                    oldAccess.state().scope(),
                    oldAccess.tokenDigest(),
                    oldAccess.state().currentIpDigest());
            if (ips.isEmpty()) {
                // VERIFIED 却解密为空说明旧证据已不满足 v7 不变量；轮换后重新探测，不能继承伪成功。
                return new WebRtcRotationState(
                        PreAuthWebRtcPhase.REQUIRED,
                        nextGeneration(oldAccess.state().webRtcGeneration()),
                        List.of());
            }
            return new WebRtcRotationState(
                    PreAuthWebRtcPhase.VERIFIED,
                    oldAccess.state().webRtcGeneration(),
                    ips);
        } catch (WebRtcIpProtectionException exception) {
            // 无法验证旧证据时不继承成功；新 Token 进入全新的 REQUIRED，避免永久锁死合法会话。
            return new WebRtcRotationState(
                    PreAuthWebRtcPhase.REQUIRED,
                    nextGeneration(oldAccess.state().webRtcGeneration()),
                    List.of());
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
    private record WebRtcRotationState(
            PreAuthWebRtcPhase phase,
            long generation,
            List<String> ips) {

        private WebRtcRotationState {
            ips = List.copyOf(ips);
        }
    }

    private static long nextGeneration(long generation) {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("WebRTC generation is exhausted.");
        }
        return generation + 1L;
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
