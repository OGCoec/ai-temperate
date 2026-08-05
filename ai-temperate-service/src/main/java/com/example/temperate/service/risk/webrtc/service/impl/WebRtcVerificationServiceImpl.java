package com.example.temperate.service.risk.webrtc.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcBeginResult;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcFailureReason;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcWriteResult;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtectionException;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtector;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.service.risk.webrtc.validation.WebRtcInvalidReportException;
import com.example.temperate.service.risk.webrtc.validation.WebRtcIpNormalizer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 使用 PreAuth v6 与 Redis 时间实现 WebRTC 完全异步四态门禁。
 *
 * <p>业务请求读取 REQUIRED/PENDING 后立即放行；只有 GET start 能把 REQUIRED 原子转换为
 * PENDING，report 只能完成当前 PENDING generation，任何终态都不能被迟到结果覆盖。</p>
 */
@Service
public final class WebRtcVerificationServiceImpl
        implements WebRtcVerificationService {

    private final NetworkRiskProperties properties;
    private final PreAuthStore preAuthStore;
    private final NetworkRiskIdentifier identifier;
    private final WebRtcIpProtector protector;
    private final WebRtcIpNormalizer normalizer;

    public WebRtcVerificationServiceImpl(
            NetworkRiskProperties properties,
            PreAuthStore preAuthStore,
            NetworkRiskIdentifier identifier,
            WebRtcIpProtector protector,
            WebRtcIpNormalizer normalizer) {
        this.properties = Objects.requireNonNull(properties);
        this.preAuthStore = Objects.requireNonNull(preAuthStore);
        this.identifier = Objects.requireNonNull(identifier);
        this.protector = Objects.requireNonNull(protector);
        this.normalizer = Objects.requireNonNull(normalizer);
    }

    @Override
    public WebRtcVerificationDecision begin(
            PreAuthAccess access,
            String currentHttpIp) {
        Objects.requireNonNull(access);
        PreAuthState state = access.state();
        HmacIdentifier requestIpDigest = identifier.identifyIp(currentHttpIp);
        if (!requestIpDigest.equals(state.currentIpDigest())) {
            return WebRtcVerificationDecision.networkChanged();
        }
        PreAuthWebRtcBeginResult result = preAuthStore.beginWebRtcVerification(
                state.scope(),
                access.tokenDigest(),
                state.deviceDigest(),
                state.currentIpDigest(),
                state.webRtcGeneration(),
                properties.webRtc().pendingWindow(),
                ttl(state));
        return switch (result.status()) {
            case STARTED, PENDING_PRESERVED -> WebRtcVerificationDecision.pending(
                    result.generation(),
                    result.deadlineAt(),
                    result.remainingMillis());
            case VERIFIED_PRESERVED, FAILURE_PRESERVED,
                    START_TIMEOUT, REPORT_TIMEOUT -> reloadAndInspect(
                            access,
                            currentHttpIp);
            case NETWORK_CHANGED -> WebRtcVerificationDecision.networkChanged();
            case STALE_GENERATION -> WebRtcVerificationDecision.stale();
            case STATE_INVALID -> WebRtcVerificationDecision.stateInvalid();
        };
    }

    @Override
    public WebRtcVerificationDecision inspect(
            PreAuthAccess access,
            String currentHttpIp) {
        Objects.requireNonNull(access);
        PreAuthState state = access.state();
        HmacIdentifier requestIpDigest = identifier.identifyIp(currentHttpIp);
        if (!requestIpDigest.equals(state.currentIpDigest())) {
            // 风险拦截器可能已在本请求中提升 generation；重读一次即可让触发变化的请求按新 REQUIRED 放行。
            return reloadAndInspect(access, currentHttpIp);
        }
        return inspectCurrentState(access, state, currentHttpIp);
    }

    @Override
    public WebRtcVerificationDecision report(
            PreAuthAccess access,
            String currentHttpIp,
            String probeGeneration,
            List<String> reportedWebRtcIps) {
        Objects.requireNonNull(access);
        PreAuthState state = access.state();
        long generation = parseGeneration(probeGeneration);
        if (generation != state.webRtcGeneration()) {
            return WebRtcVerificationDecision.stale();
        }
        HmacIdentifier requestIpDigest = identifier.identifyIp(currentHttpIp);
        if (!requestIpDigest.equals(state.currentIpDigest())) {
            return WebRtcVerificationDecision.networkChanged();
        }
        if (state.webRtcPhase() != PreAuthWebRtcPhase.PENDING) {
            // report 不允许隐式 start；终态返回服务端现状，REQUIRED 视为旧任务提交。
            return state.webRtcPhase() == PreAuthWebRtcPhase.REQUIRED
                    ? WebRtcVerificationDecision.stale()
                    : inspectCurrentState(access, state, currentHttpIp);
        }

        List<String> normalized = normalizer.normalizeReported(
                reportedWebRtcIps,
                properties.webRtc().maxReportedIps());
        String canonicalHttpIp = normalizer.normalizeTrustedHttpIp(currentHttpIp);
        boolean matched = normalized.contains(canonicalHttpIp);
        PreAuthWebRtcFailureReason failureReason = matched
                ? null
                : normalized.isEmpty()
                        ? PreAuthWebRtcFailureReason.NO_PUBLIC_CANDIDATE
                        : PreAuthWebRtcFailureReason.IP_MISMATCH;
        // 只有成功或 IP 不一致需要保留候选证据；空候选失败禁止产生无意义密文。
        String encrypted = normalized.isEmpty()
                ? null
                : protector.encrypt(
                        normalized,
                        state.scope(),
                        access.tokenDigest(),
                        state.currentIpDigest());
        PreAuthWebRtcWriteResult writeResult = preAuthStore.writeWebRtcResult(
                state.scope(),
                access.tokenDigest(),
                state.deviceDigest(),
                state.currentIpDigest(),
                generation,
                matched,
                failureReason,
                encrypted,
                !normalized.isEmpty(),
                ttl(state));
        return switch (writeResult) {
            case UPDATED -> matched
                    ? WebRtcVerificationDecision.verified(generation, normalized)
                    : WebRtcVerificationDecision.failed(
                            generation,
                            failureReason,
                            normalized);
            case VERIFIED_PRESERVED, FAILURE_PRESERVED -> reloadAndInspect(
                    access,
                    currentHttpIp);
            case DEADLINE_EXPIRED -> WebRtcVerificationDecision.failed(
                    generation,
                    PreAuthWebRtcFailureReason.REPORT_TIMEOUT,
                    List.of());
            case STALE_GENERATION -> WebRtcVerificationDecision.stale();
            case NETWORK_CHANGED -> WebRtcVerificationDecision.networkChanged();
            case PREAUTH_UNAVAILABLE -> WebRtcVerificationDecision.stateInvalid();
        };
    }

    private WebRtcVerificationDecision inspectCurrentState(
            PreAuthAccess access,
            PreAuthState state,
            String currentHttpIp) {
        return switch (state.webRtcPhase()) {
            case REQUIRED, PENDING -> inspectOpenState(
                    access,
                    state,
                    currentHttpIp);
            case VERIFIED -> inspectVerified(access, state, currentHttpIp);
            case FAILED -> inspectFailed(access, state);
        };
    }

    private WebRtcVerificationDecision inspectOpenState(
            PreAuthAccess access,
            PreAuthState state,
            String currentHttpIp) {
        // Lua 使用 Redis TIME 判定超时；Java 节点时间只用于业务快照，不参与安全截止线。
        boolean expired = preAuthStore.expireWebRtcDeadline(
                state.scope(),
                access.tokenDigest(),
                state.deviceDigest(),
                state.currentIpDigest(),
                state.webRtcGeneration(),
                ttl(state));
        if (expired) {
            return reloadAndInspect(access, currentHttpIp);
        }
        return state.webRtcPhase() == PreAuthWebRtcPhase.REQUIRED
                ? WebRtcVerificationDecision.required(
                        state.webRtcGeneration(),
                        state.webRtcDeadlineAt())
                : WebRtcVerificationDecision.pending(
                        state.webRtcGeneration(),
                        state.webRtcDeadlineAt());
    }

    private WebRtcVerificationDecision inspectVerified(
            PreAuthAccess access,
            PreAuthState state,
            String currentHttpIp) {
        List<String> ips = decrypt(access, state);
        if (ips == null) {
            return WebRtcVerificationDecision.stateInvalid();
        }
        String canonicalHttpIp = normalizer.normalizeTrustedHttpIp(currentHttpIp);
        return ips.contains(canonicalHttpIp)
                ? WebRtcVerificationDecision.verified(state.webRtcGeneration(), ips)
                : WebRtcVerificationDecision.stateInvalid();
    }

    private WebRtcVerificationDecision inspectFailed(
            PreAuthAccess access,
            PreAuthState state) {
        List<String> ips = state.webRtcIps() == null ? List.of() : decrypt(access, state);
        if (ips == null) {
            return WebRtcVerificationDecision.stateInvalid();
        }
        return WebRtcVerificationDecision.failed(
                state.webRtcGeneration(),
                state.webRtcFailureReason(),
                ips);
    }

    private List<String> decrypt(PreAuthAccess access, PreAuthState state) {
        try {
            return normalizer.normalizeReported(
                    protector.decrypt(
                            state.webRtcIps(),
                            state.scope(),
                            access.tokenDigest(),
                            state.currentIpDigest()),
                    properties.webRtc().maxReportedIps());
        } catch (WebRtcIpProtectionException | WebRtcInvalidReportException exception) {
            return null;
        }
    }

    private WebRtcVerificationDecision reloadAndInspect(
            PreAuthAccess access,
            String currentHttpIp) {
        HmacIdentifier requestIpDigest = identifier.identifyIp(currentHttpIp);
        return preAuthStore.find(access.state().scope(), access.tokenDigest())
                .map(state -> requestIpDigest.equals(state.currentIpDigest())
                        ? inspectCurrentState(
                                new PreAuthAccess(access.tokenDigest(), state),
                                state,
                                currentHttpIp)
                        : WebRtcVerificationDecision.networkChanged())
                .orElseGet(WebRtcVerificationDecision::stateInvalid);
    }

    private static long parseGeneration(String value) {
        if (value == null || !value.matches("^[1-9][0-9]{0,18}$")) {
            throw new WebRtcInvalidReportException();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new WebRtcInvalidReportException();
        }
    }

    private Duration ttl(PreAuthState state) {
        return state.authenticated()
                ? properties.authenticatedPreAuthTtl()
                : properties.anonymousPreAuthTtl();
    }
}
