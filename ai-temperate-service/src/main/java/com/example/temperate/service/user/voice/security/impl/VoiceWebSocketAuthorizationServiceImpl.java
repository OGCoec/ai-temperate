package com.example.temperate.service.user.voice.security.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.device.service.GlobalDeviceBlockService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.access.dto.SessionBindingAccessCommand;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationOutcome;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.security.VoiceHandshakeCommand;
import com.example.temperate.service.user.voice.security.VoiceHandshakePrincipal;
import com.example.temperate.service.user.voice.security.VoiceTicketIssueCommand;
import com.example.temperate.service.user.voice.security.VoiceWebSocketAuthorizationService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.example.temperate.service.user.voice.ticket.VoiceTicketSecurityBinding;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在 Voice Ticket 签发和 WebSocket 返回 101 前复用同一套设备、PreAuth、WebRTC 与登录状态规则。
 *
 * <p>Ticket 先被原子消费，再重新读取所有可撤销状态；消费后的失败不可恢复，避免同一安全信封被重放。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.voice", name = "enabled", havingValue = "true")
public final class VoiceWebSocketAuthorizationServiceImpl
        implements VoiceWebSocketAuthorizationService {

    private final VoiceSessionTicketService ticketService;
    private final GlobalDeviceBlockService deviceBlockService;
    private final AuthSessionSecretProtector protector;
    private final PreAuthService preAuthService;
    private final WebRtcVerificationService webRtcService;
    private final AccessSessionService accessSessionService;

    public VoiceWebSocketAuthorizationServiceImpl(
            VoiceSessionTicketService ticketService,
            GlobalDeviceBlockService deviceBlockService,
            AuthSessionSecretProtector protector,
            PreAuthService preAuthService,
            WebRtcVerificationService webRtcService,
            AccessSessionService accessSessionService) {
        this.ticketService = Objects.requireNonNull(ticketService);
        this.deviceBlockService = Objects.requireNonNull(deviceBlockService);
        this.protector = Objects.requireNonNull(protector);
        this.preAuthService = Objects.requireNonNull(preAuthService);
        this.webRtcService = Objects.requireNonNull(webRtcService);
        this.accessSessionService = Objects.requireNonNull(accessSessionService);
    }

    @Override
    public VoiceSessionTicketIssue issueTicket(VoiceTicketIssueCommand command) {
        VoiceTicketIssueCommand valid = Objects.requireNonNull(command);
        SessionPrincipal principal = Objects.requireNonNull(valid.principal());
        PreAuthAccess access = requireVerifiedAccess(valid.preAuthAccess());
        requireUserBinding(access);
        requireNotBlocked(valid.deviceInstallationId());

        HmacIdentifier refreshDigest = protectRefresh(valid.rawRefreshToken());
        HmacIdentifier sessionDeviceDigest = protectDevice(valid.deviceInstallationId());
        VoiceTicketSecurityBinding binding = new VoiceTicketSecurityBinding(
                principal.userId(),
                Objects.requireNonNull(valid.platform()),
                access.tokenDigest().value(),
                access.state().deviceDigest().value(),
                access.state().sessionRefDigest().value(),
                refreshDigest.value(),
                sessionDeviceDigest.value(),
                protector.deviceBlock(valid.deviceInstallationId()).value(),
                access.state().webRtcGeneration());
        return ticketService.issue(binding, valid.deviceInstallationId());
    }

    @Override
    public VoiceHandshakePrincipal authorize(VoiceHandshakeCommand command) {
        VoiceHandshakeCommand valid = Objects.requireNonNull(command);
        VoiceSessionTicketSnapshot snapshot = ticketService.consume(valid.rawTicket());
        VoiceTicketSecurityBinding binding = snapshot.binding();
        if (binding.platform() != valid.platform()) {
            throw failure(VoiceErrorCode.VOICE_TICKET_INVALID);
        }
        requireOriginShape(valid.platform(), valid.originPresent());
        requireNotBlockedDigest(binding.globalDeviceBlockDigest());

        PreAuthAccess access = preAuthService.resolveBound(
                        RiskScope.USER,
                        digest(binding.preAuthTokenDigest()),
                        digest(binding.preAuthDeviceDigest()),
                        RiskSessionType.USER_REFRESH,
                        digest(binding.sessionReferenceDigest()))
                .orElseThrow(() -> failure(VoiceErrorCode.VOICE_PREAUTH_REQUIRED));

        WebRtcVerificationDecision decision;
        try {
            decision = webRtcService.inspect(access, valid.currentHttpIp());
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        if (decision.outcome() != WebRtcVerificationOutcome.VERIFIED
                || decision.probeGeneration() != binding.webRtcGeneration()) {
            throw failure(VoiceErrorCode.VOICE_WEBRTC_REQUIRED);
        }

        final SessionPrincipal sessionPrincipal;
        try {
            sessionPrincipal = accessSessionService.validateActiveBinding(
                    new SessionBindingAccessCommand(
                            binding.userId(),
                            digest(binding.refreshSessionDigest()),
                            digest(binding.sessionDeviceDigest())));
        } catch (SessionAuthenticationException exception) {
            if (exception.code() == SessionAuthenticationErrorCode.INFRASTRUCTURE_UNAVAILABLE) {
                throw unavailable(exception);
            }
            throw new VoiceException(
                    VoiceErrorCode.VOICE_SESSION_INVALID,
                    "Voice login session is no longer active.",
                    false,
                    exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        return new VoiceHandshakePrincipal(
                sessionPrincipal.userId(),
                sessionPrincipal.publicId(),
                sessionPrincipal.displayName(),
                binding.platform());
    }

    private PreAuthAccess requireVerifiedAccess(PreAuthAccess access) {
        if (access == null || access.state().scope() != RiskScope.USER
                || access.state().webRtcPhase() != PreAuthWebRtcPhase.VERIFIED
                || access.state().webRtcGeneration() <= 0) {
            throw failure(VoiceErrorCode.VOICE_WEBRTC_REQUIRED);
        }
        return access;
    }

    private static void requireUserBinding(PreAuthAccess access) {
        if (access.state().sessionType() != RiskSessionType.USER_REFRESH
                || access.state().sessionRefDigest() == null) {
            throw failure(VoiceErrorCode.VOICE_PREAUTH_REQUIRED);
        }
    }

    private void requireNotBlocked(String rawDeviceId) {
        try {
            if (!deviceBlockService.remainingBlockTtl(rawDeviceId).isZero()) {
                throw failure(VoiceErrorCode.VOICE_DEVICE_BLOCKED);
            }
        } catch (VoiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private void requireNotBlockedDigest(String value) {
        try {
            Duration ttl = deviceBlockService.remainingBlockTtlByDigest(digest(value));
            if (!ttl.isZero()) {
                throw failure(VoiceErrorCode.VOICE_DEVICE_BLOCKED);
            }
        } catch (VoiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private HmacIdentifier protectRefresh(String value) {
        try {
            return protector.refreshToken(value);
        } catch (RuntimeException exception) {
            throw failure(VoiceErrorCode.VOICE_SESSION_INVALID);
        }
    }

    private HmacIdentifier protectDevice(String value) {
        try {
            return protector.device(value);
        } catch (RuntimeException exception) {
            throw failure(VoiceErrorCode.VOICE_PREAUTH_REQUIRED);
        }
    }

    private static HmacIdentifier digest(String value) {
        try {
            return HmacIdentifier.fromProtectedValue(value);
        } catch (RuntimeException exception) {
            throw failure(VoiceErrorCode.VOICE_TICKET_INVALID);
        }
    }

    private static void requireOriginShape(
            VoiceClientPlatform platform,
            boolean originPresent) {
        if ((platform == VoiceClientPlatform.H5) != originPresent) {
            throw failure(VoiceErrorCode.VOICE_TICKET_INVALID);
        }
    }

    private static VoiceException failure(VoiceErrorCode code) {
        return new VoiceException(code, "Voice WebSocket authorization failed.", false);
    }

    private static VoiceException unavailable(Throwable cause) {
        return new VoiceException(
                VoiceErrorCode.VOICE_INFRASTRUCTURE_UNAVAILABLE,
                "Voice WebSocket authorization is temporarily unavailable.",
                true,
                cause);
    }
}
