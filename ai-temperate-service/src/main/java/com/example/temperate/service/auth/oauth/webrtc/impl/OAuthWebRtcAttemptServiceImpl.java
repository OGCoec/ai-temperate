package com.example.temperate.service.auth.oauth.webrtc.impl;

import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcAttemptService;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.AttemptLookup;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.ResumeStoreCommand;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.ResumeStoreResult;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.SuspendStoreCommand;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.SuspendStoreResult;
import com.example.temperate.service.auth.oauth.webrtc.store.OAuthWebRtcAttemptStore.VerdictStoreResult;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 实现 H5 OAuth 对既有 WebRTC generation 的暂停、恢复与只读裁决查询，避免回调页创建第二套探测任务。
 */
@Service
public final class OAuthWebRtcAttemptServiceImpl implements OAuthWebRtcAttemptService {

    private final OAuthWebRtcAttemptStore store;
    private final AuthSessionSecretProtector protector;
    private final NetworkRiskProperties properties;

    public OAuthWebRtcAttemptServiceImpl(
            OAuthWebRtcAttemptStore store,
            AuthSessionSecretProtector protector,
            NetworkRiskProperties properties) {
        this.store = Objects.requireNonNull(store);
        this.protector = Objects.requireNonNull(protector);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public SuspendResult suspend(
            ProtectedOAuthFlowAccess flow,
            PreAuthAccess preAuth,
            String probeGeneration,
            String probeRunId,
            Instant suspendExpiresAt) {
        ProtectedOAuthFlowAccess validFlow = Objects.requireNonNull(flow);
        PreAuthAccess validPreAuth = requirePreAuth(preAuth);
        long generation = generation(probeGeneration);
        String attemptId = UUID.randomUUID().toString();
        SuspendStoreResult stored = store.suspend(new SuspendStoreCommand(
                validPreAuth.state().scope(),
                validPreAuth.tokenDigest(),
                protector.oauthWebRtcAttempt(attemptId),
                validPreAuth.state().deviceDigest(),
                validPreAuth.state().currentIpDigest(),
                validFlow.flowId(),
                generation,
                protector.oauthWebRtcProbeRun(probeRunId),
                Objects.requireNonNull(suspendExpiresAt)));
        if (stored.state() == State.VERIFIED) {
            return new SuspendResult(State.VERIFIED, null,
                    Long.toString(stored.generation()), false);
        }
        if (stored.state() != State.OAUTH_SUSPENDED) {
            throw forbidden("OAuth WebRTC attempt cannot be suspended.");
        }
        return new SuspendResult(
                stored.state(), attemptId, Long.toString(stored.generation()), false);
    }

    @Override
    public ResumeResult resume(
            ProtectedOAuthFlowAccess flow,
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration) {
        ProtectedOAuthFlowAccess validFlow = Objects.requireNonNull(flow);
        PreAuthAccess validPreAuth = requirePreAuth(preAuth);
        ResumeStoreResult stored = store.resume(new ResumeStoreCommand(
                validPreAuth.state().scope(),
                validPreAuth.tokenDigest(),
                protector.oauthWebRtcAttempt(rawAttemptId),
                validPreAuth.state().deviceDigest(),
                validPreAuth.state().currentIpDigest(),
                validFlow.flowId(),
                generation(probeGeneration),
                properties.webRtc().oauthAsyncVerdictWindow()));
        if (stored.state() != State.RESUMED
                && stored.state() != State.REPLACED
                && stored.state() != State.VERIFIED) {
            throw forbidden("OAuth WebRTC attempt cannot be resumed.");
        }
        return new ResumeResult(
                stored.state(), rawAttemptId,
                Long.toString(stored.generation()), stored.fallbackUsed());
    }

    @Override
    public boolean isPendingH5OAuthCompletionAllowed(
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration) {
        return store.canComplete(lookup(preAuth, rawAttemptId, probeGeneration));
    }

    @Override
    public VerdictStatus verdictStatus(
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration) {
        VerdictStoreResult result = store.inspect(
                lookup(preAuth, rawAttemptId, probeGeneration));
        return new VerdictStatus(
                result.state(), Long.toString(result.generation()),
                result.verdictDeadlineAt());
    }

    private AttemptLookup lookup(
            PreAuthAccess preAuth,
            String rawAttemptId,
            String probeGeneration) {
        PreAuthAccess valid = requirePreAuth(preAuth);
        return new AttemptLookup(
                valid.state().scope(),
                valid.tokenDigest(),
                protector.oauthWebRtcAttempt(rawAttemptId),
                valid.state().deviceDigest(),
                valid.state().currentIpDigest(),
                generation(probeGeneration));
    }

    private static PreAuthAccess requirePreAuth(PreAuthAccess access) {
        if (access == null || access.state() == null) {
            throw forbidden("OAuth WebRTC PreAuth is missing.");
        }
        return access;
    }

    private static long generation(String value) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw forbidden("OAuth WebRTC generation is invalid.");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw forbidden("OAuth WebRTC generation is invalid.");
        }
    }

    private static OAuthFlowException forbidden(String message) {
        return new OAuthFlowException(OAuthFlowErrorCode.FLOW_FORBIDDEN, message);
    }
}
