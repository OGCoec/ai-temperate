package com.example.temperate.service.auth.oauth.webrtc.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcSessionVerdictService;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.PendingSessionCommand;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.PendingStoreResult;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.ReportDecisionCommand;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthIssue;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 实现 H5 OAuth 登录后十五秒乐观裁决，并把 PreAuth、attempt 与 Refresh Session 放入同一 Redis 原子边界。
 */
@Service
public final class OAuthWebRtcSessionVerdictServiceImpl
        implements OAuthWebRtcSessionVerdictService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OAuthWebRtcSessionVerdictServiceImpl.class);
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_ATTEMPTS = 4;

    private final OAuthWebRtcAttemptStore store;
    private final AuthSessionSecretProtector authProtector;
    private final NetworkRiskIdentifier riskIdentifier;
    private final NetworkRiskProperties properties;
    private final Duration verdictWindow;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthWebRtcSessionVerdictServiceImpl(
            OAuthWebRtcAttemptStore store,
            AuthSessionSecretProtector authProtector,
            NetworkRiskIdentifier riskIdentifier,
            NetworkRiskProperties properties) {
        this.store = Objects.requireNonNull(store);
        this.authProtector = Objects.requireNonNull(authProtector);
        this.riskIdentifier = Objects.requireNonNull(riskIdentifier);
        this.properties = Objects.requireNonNull(properties);
        this.verdictWindow = requireVerdictWindow(properties.webRtc().oauthAsyncVerdictWindow());
    }

    @Override
    public PendingSession issuePendingOAuthVerdict(
            ProtectedOAuthFlowAccess flow,
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration,
            String rawRefreshToken,
            String rawDeviceId,
            Instant seenAt) {
        Objects.requireNonNull(flow);
        PreAuthAccess valid = Objects.requireNonNull(preAuth);
        long generation = parseGeneration(probeGeneration);
        for (int attempt = 0; attempt < TOKEN_ATTEMPTS; attempt++) {
            String newRawToken = randomToken();
            HmacIdentifier newTokenDigest = riskIdentifier.identifyPreAuthToken(newRawToken);
            HmacIdentifier contextDigest = riskIdentifier.identifyDecisionContext(
                    valid.state().scope().name()
                            + "|" + newTokenDigest.value()
                            + "|" + valid.state().deviceDigest().value()
                            + "|" + valid.state().currentIpDigest().value());
            PendingStoreResult result = store.issuePendingSession(new PendingSessionCommand(
                    valid.state().scope(),
                    valid.tokenDigest(),
                    newTokenDigest,
                    authProtector.oauthWebRtcAttempt(rawAttemptId),
                    valid.state().deviceDigest(),
                    valid.state().currentIpDigest(),
                    flow.flowId(),
                    generation,
                    riskIdentifier.identifySession(rawRefreshToken),
                    authProtector.refreshToken(rawRefreshToken),
                    authProtector.device(rawDeviceId),
                    contextDigest,
                    RiskSessionType.USER_REFRESH,
                    Objects.requireNonNull(seenAt),
                    verdictWindow,
                    properties.authenticatedPreAuthTtl()));
            if (result.issued()) {
                return new PendingSession(
                        new PreAuthIssue(
                                newRawToken,
                                seenAt.plus(properties.authenticatedPreAuthTtl()),
                                PreAuthWebRtcPhase.PENDING,
                                generation),
                        result.verdictDeadlineAt());
            }
        }
        throw new PreAuthRequiredException();
    }

    @Override
    public PreAuthWebRtcWriteResult decideReport(
            PreAuthAccess preAuth,
            String rawAttemptId,
            long probeGeneration,
            boolean verified,
            PreAuthWebRtcFailureReason failureReason,
            String encryptedWebRtcIps,
            boolean hasReportedIps) {
        PreAuthAccess valid = Objects.requireNonNull(preAuth);
        HmacIdentifier attemptDigest = authProtector.oauthWebRtcAttempt(rawAttemptId);
        boolean convergingVerified = valid.state().webRtcPhase() == PreAuthWebRtcPhase.VERIFIED;
        PreAuthWebRtcWriteResult result = store.decideReport(new ReportDecisionCommand(
                valid.state().scope(),
                valid.tokenDigest(),
                attemptDigest,
                valid.state().deviceDigest(),
                valid.state().currentIpDigest(),
                probeGeneration,
                verified,
                failureReason,
                encryptedWebRtcIps,
                hasReportedIps,
                properties.authenticatedPreAuthTtl()));
        if (verified && result == PreAuthWebRtcWriteResult.UPDATED) {
            LOGGER.info(
                    "event=oauth_webrtc_session_activated traceId={} clientRequestId={} "
                            + "probeRunId={} generation={} attemptDigestPrefix={} writeResult={}",
                    safeMdc("traceId"),
                    safeMdc("clientRequestId"),
                    safeMdc("probeRunId"),
                    probeGeneration,
                    digestPrefix(attemptDigest),
                    result.name());
            if (convergingVerified) {
                LOGGER.info(
                        "event=oauth_webrtc_verified_converged traceId={} clientRequestId={} "
                                + "probeRunId={} generation={} attemptDigestPrefix={}",
                        safeMdc("traceId"),
                        safeMdc("clientRequestId"),
                        safeMdc("probeRunId"),
                        probeGeneration,
                        digestPrefix(attemptDigest));
            }
        }
        return result;
    }

    private static String digestPrefix(HmacIdentifier identifier) {
        String value = identifier.value();
        return value.substring(0, Math.min(12, value.length()));
    }

    private static String safeMdc(String key) {
        String value = MDC.get(key);
        if (value == null || !value.matches("^[A-Za-z0-9._:-]{1,128}$")) {
            return "absent";
        }
        return value;
    }

    private String randomToken() {
        byte[] value = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Duration requireVerdictWindow(Duration value) {
        if (value == null || value.isNegative() || value.isZero()
                || value.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException(
                    "OAuth WebRTC async verdict window must be between 1 ms and 15 seconds.");
        }
        return value;
    }

    private static long parseGeneration(String value) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw new PreAuthRequiredException();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new PreAuthRequiredException();
        }
    }
}
