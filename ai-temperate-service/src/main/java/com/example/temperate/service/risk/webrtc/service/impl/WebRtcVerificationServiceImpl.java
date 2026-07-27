package com.example.temperate.service.risk.webrtc.service.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
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
 * 使用当前可信 HTTP IP、PreAuth 绑定摘要和加密候选集合实现 WebRTC 三态校验。
 *
 * <p>该实现不发起 STUN，也不保存请求级可变状态；IP 变化、成功优先级和两字段一致性由 Redis Lua
 * 再次校验，避免浏览器探测期间的网络切换或迟到结果破坏已验证状态。</p>
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
    public WebRtcVerificationDecision inspect(
            PreAuthAccess access,
            String currentHttpIp) {
        Objects.requireNonNull(access);
        PreAuthState state = access.state();
        HmacIdentifier requestIpDigest = identifier.identifyIp(currentHttpIp);
        if (!requestIpDigest.equals(state.currentIpDigest())) {
            return WebRtcVerificationDecision.required();
        }
        if (state.webRtcStatus() == null) {
            return WebRtcVerificationDecision.required();
        }

        final List<String> decryptedIps;
        try {
            decryptedIps = normalizer.normalizeReported(
                    protector.decrypt(
                            state.webRtcIps(),
                            state.scope(),
                            access.tokenDigest(),
                            state.currentIpDigest()),
                    properties.webRtc().maxReportedIps());
        } catch (WebRtcIpProtectionException | WebRtcInvalidReportException exception) {
            clearInvalidState(access);
            return WebRtcVerificationDecision.required();
        }

        String canonicalHttpIp = normalizer.normalizeTrustedHttpIp(currentHttpIp);
        boolean containsCurrent = decryptedIps.contains(canonicalHttpIp);
        if (Boolean.TRUE.equals(state.webRtcStatus())) {
            if (containsCurrent) {
                return WebRtcVerificationDecision.verified(decryptedIps);
            }
            clearInvalidState(access);
            return WebRtcVerificationDecision.required();
        }
        if (decryptedIps.isEmpty()) {
            return WebRtcVerificationDecision.failed();
        }
        if (containsCurrent) {
            clearInvalidState(access);
            return WebRtcVerificationDecision.required();
        }
        return WebRtcVerificationDecision.mismatch(decryptedIps);
    }

    @Override
    public WebRtcVerificationDecision report(
            PreAuthAccess access,
            String currentHttpIp,
            List<String> reportedWebRtcIps) {
        Objects.requireNonNull(access);
        PreAuthState state = access.state();
        HmacIdentifier requestIpDigest = identifier.identifyIp(currentHttpIp);
        if (!requestIpDigest.equals(state.currentIpDigest())) {
            return WebRtcVerificationDecision.networkChanged();
        }
        List<String> normalized = normalizer.normalizeReported(
                reportedWebRtcIps,
                properties.webRtc().maxReportedIps());
        String canonicalHttpIp = normalizer.normalizeTrustedHttpIp(currentHttpIp);
        boolean matched = normalized.contains(canonicalHttpIp);
        String encrypted = protector.encrypt(
                normalized,
                state.scope(),
                access.tokenDigest(),
                state.currentIpDigest());
        PreAuthWebRtcWriteResult writeResult = preAuthStore.writeWebRtcResult(
                state.scope(),
                access.tokenDigest(),
                state.deviceDigest(),
                state.currentIpDigest(),
                matched,
                encrypted,
                !normalized.isEmpty(),
                ttl(state));
        return switch (writeResult) {
            case UPDATED -> decisionForReport(matched, normalized);
            case NETWORK_CHANGED -> WebRtcVerificationDecision.networkChanged();
            case PREAUTH_UNAVAILABLE -> WebRtcVerificationDecision.required();
            case VERIFIED_PRESERVED, FAILURE_PRESERVED -> preAuthStore
                    .find(state.scope(), access.tokenDigest())
                    .map(current -> inspect(
                            new PreAuthAccess(access.tokenDigest(), current),
                            currentHttpIp))
                    .orElseGet(WebRtcVerificationDecision::required);
        };
    }

    private WebRtcVerificationDecision decisionForReport(
            boolean matched,
            List<String> normalized) {
        if (matched) {
            return WebRtcVerificationDecision.verified(normalized);
        }
        return normalized.isEmpty()
                ? WebRtcVerificationDecision.failed()
                : WebRtcVerificationDecision.mismatch(normalized);
    }

    private void clearInvalidState(PreAuthAccess access) {
        PreAuthState state = access.state();
        preAuthStore.clearWebRtcResult(
                state.scope(),
                access.tokenDigest(),
                state.deviceDigest(),
                state.currentIpDigest(),
                ttl(state));
    }

    private Duration ttl(PreAuthState state) {
        return state.authenticated()
                ? properties.authenticatedPreAuthTtl()
                : properties.anonymousPreAuthTtl();
    }
}
