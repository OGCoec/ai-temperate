package com.example.temperate.service.user.voice.security.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.device.service.GlobalDeviceBlockService;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.access.AccessSessionService;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.auth.session.authentication.enums.SessionAuthenticationErrorCode;
import com.example.temperate.service.auth.session.authentication.exception.SessionAuthenticationException;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.service.PreAuthService;
import com.example.temperate.service.risk.webrtc.service.WebRtcVerificationService;
import com.example.temperate.service.risk.webrtc.domain.WebRtcVerificationDecision;
import com.example.temperate.service.user.voice.VoiceClientPlatform;
import com.example.temperate.service.user.voice.VoiceErrorCode;
import com.example.temperate.service.user.voice.VoiceException;
import com.example.temperate.service.user.voice.security.VoiceHandshakeCommand;
import com.example.temperate.service.user.voice.security.VoiceHandshakePrincipal;
import com.example.temperate.service.user.voice.security.VoiceTicketIssueCommand;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketService;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketIssue;
import com.example.temperate.service.user.voice.ticket.VoiceSessionTicketSnapshot;
import com.example.temperate.service.user.voice.ticket.VoiceTicketSecurityBinding;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证 Voice v2 授权在签发与握手阶段均严格拒绝过期或不匹配的安全状态。
 */
final class VoiceWebSocketAuthorizationServiceImplTest {

    private final VoiceSessionTicketService ticketService = mock(
            VoiceSessionTicketService.class);
    private final GlobalDeviceBlockService blockService = mock(
            GlobalDeviceBlockService.class);
    private final AuthSessionSecretProtector protector = mock(
            AuthSessionSecretProtector.class);
    private final PreAuthService preAuthService = mock(PreAuthService.class);
    private final WebRtcVerificationService webRtcService = mock(
            WebRtcVerificationService.class);
    private final AccessSessionService accessSessionService = mock(
            AccessSessionService.class);
    private final VoiceWebSocketAuthorizationServiceImpl service =
            new VoiceWebSocketAuthorizationServiceImpl(
                    ticketService,
                    blockService,
                    protector,
                    preAuthService,
                    webRtcService,
                    accessSessionService);

    @Test
    void refusesToIssueUnlessWebRtcIsStrictlyVerified() {
        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthState state = mock(PreAuthState.class);
        when(access.state()).thenReturn(state);
        when(state.scope()).thenReturn(RiskScope.USER);

        for (PreAuthWebRtcPhase phase : List.of(
                PreAuthWebRtcPhase.REQUIRED,
                PreAuthWebRtcPhase.PENDING,
                PreAuthWebRtcPhase.FAILED)) {
            when(state.webRtcPhase()).thenReturn(phase);
            assertThatThrownBy(() -> service.issueTicket(new VoiceTicketIssueCommand(
                    new SessionPrincipal(10001L, "AAAAAAAAAAA", "用户"),
                    VoiceClientPlatform.H5,
                    "550e8400-e29b-41d4-a716-446655440000",
                    "A".repeat(38),
                    access)))
                    .isInstanceOfSatisfying(VoiceException.class,
                            exception -> assertThat(exception.code())
                                    .isEqualTo(VoiceErrorCode.VOICE_WEBRTC_REQUIRED));
        }
        verifyNoInteractions(ticketService);
    }

    @Test
    void refusesToIssueForAGloballyBlockedDevice() {
        PreAuthAccess access = verifiedAccess();
        when(blockService.remainingBlockTtl(
                "550e8400-e29b-41d4-a716-446655440000"))
                .thenReturn(Duration.ofSeconds(20));

        assertThatThrownBy(() -> service.issueTicket(new VoiceTicketIssueCommand(
                new SessionPrincipal(10001L, "AAAAAAAAAAA", "用户"),
                VoiceClientPlatform.ANDROID,
                "550e8400-e29b-41d4-a716-446655440000",
                "R".repeat(38),
                access)))
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(VoiceErrorCode.VOICE_DEVICE_BLOCKED));
        verifyNoInteractions(ticketService, protector);
    }

    @Test
    void issuesSchemaTwoEnvelopeOnlyForACompletelyVerifiedBinding() {
        PreAuthAccess access = verifiedAccess();
        when(blockService.remainingBlockTtl(
                "550e8400-e29b-41d4-a716-446655440000"))
                .thenReturn(Duration.ZERO);
        when(protector.refreshToken("R".repeat(38))).thenReturn(id('D'));
        when(protector.device("550e8400-e29b-41d4-a716-446655440000"))
                .thenReturn(id('E'));
        when(protector.deviceBlock("550e8400-e29b-41d4-a716-446655440000"))
                .thenReturn(id('F'));
        VoiceSessionTicketIssue expected = new VoiceSessionTicketIssue(
                "T".repeat(43), Instant.now().plusSeconds(30));
        when(ticketService.issue(
                org.mockito.ArgumentMatchers.any(VoiceTicketSecurityBinding.class),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(expected);

        VoiceSessionTicketIssue issued = service.issueTicket(new VoiceTicketIssueCommand(
                new SessionPrincipal(10001L, "AAAAAAAAAAA", "用户"),
                VoiceClientPlatform.H5,
                "550e8400-e29b-41d4-a716-446655440000",
                "R".repeat(38),
                access));

        ArgumentCaptor<VoiceTicketSecurityBinding> binding = ArgumentCaptor.forClass(
                VoiceTicketSecurityBinding.class);
        verify(ticketService).issue(
                binding.capture(),
                org.mockito.ArgumentMatchers.eq(
                        "550e8400-e29b-41d4-a716-446655440000"));
        assertThat(issued).isSameAs(expected);
        assertThat(binding.getValue().preAuthTokenDigest()).isEqualTo("A".repeat(43));
        assertThat(binding.getValue().refreshSessionDigest()).isEqualTo("D".repeat(43));
        assertThat(binding.getValue().webRtcGeneration()).isEqualTo(7L);
        assertThat(binding.getValue().toString())
                .doesNotContain("550e8400", "R".repeat(38));
    }

    @Test
    void authorizesAfterRecheckingEveryCurrentBinding() {
        VoiceTicketSecurityBinding binding = binding(VoiceClientPlatform.H5);
        PreAuthAccess access = verifiedAccess();
        when(ticketService.consume("G".repeat(43))).thenReturn(
                new VoiceSessionTicketSnapshot(2, binding, Instant.now().plusSeconds(30)));
        when(blockService.remainingBlockTtlByDigest(id('F'))).thenReturn(Duration.ZERO);
        when(preAuthService.resolveBound(
                RiskScope.USER,
                id('A'),
                id('B'),
                com.example.temperate.service.risk.domain.RiskSessionType.USER_REFRESH,
                id('C'))).thenReturn(Optional.of(access));
        when(webRtcService.inspect(access, "203.0.113.10"))
                .thenReturn(WebRtcVerificationDecision.verified(
                        7L, List.of("203.0.113.10")));
        SessionPrincipal current = new SessionPrincipal(10001L, "AAAAAAAAAAA", "用户");
        when(accessSessionService.validateActiveBinding(
                org.mockito.ArgumentMatchers.any())).thenReturn(current);

        VoiceHandshakePrincipal principal = service.authorize(new VoiceHandshakeCommand(
                "G".repeat(43),
                VoiceClientPlatform.H5,
                true,
                "203.0.113.10"));

        assertThat(principal.userId()).isEqualTo(10001L);
        assertThat(principal.platform()).isEqualTo(VoiceClientPlatform.H5);
        verify(webRtcService).inspect(access, "203.0.113.10");
        verify(accessSessionService).validateActiveBinding(
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void consumesThenRejectsPlatformMismatchWithoutCheckingOtherState() {
        VoiceTicketSecurityBinding binding = binding(VoiceClientPlatform.H5);
        when(ticketService.consume("G".repeat(43))).thenReturn(
                new VoiceSessionTicketSnapshot(2, binding, Instant.now().plusSeconds(30)));

        assertThatThrownBy(() -> service.authorize(new VoiceHandshakeCommand(
                "G".repeat(43),
                VoiceClientPlatform.ANDROID,
                false,
                "203.0.113.10")))
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(VoiceErrorCode.VOICE_TICKET_INVALID));
        verifyNoInteractions(preAuthService, webRtcService, accessSessionService);
    }

    @Test
    void rejectsWhenCurrentPreAuthBindingNoLongerExists() {
        prepareConsumedH5Ticket();
        when(blockService.remainingBlockTtlByDigest(id('F'))).thenReturn(Duration.ZERO);
        when(preAuthService.resolveBound(
                RiskScope.USER,
                id('A'),
                id('B'),
                com.example.temperate.service.risk.domain.RiskSessionType.USER_REFRESH,
                id('C'))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorizeH5())
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(VoiceErrorCode.VOICE_PREAUTH_REQUIRED));
        verifyNoInteractions(webRtcService, accessSessionService);
    }

    @Test
    void rejectsWhenCurrentWebRtcGenerationChanged() {
        PreAuthAccess access = prepareCurrentPreAuth();
        when(webRtcService.inspect(access, "203.0.113.10"))
                .thenReturn(WebRtcVerificationDecision.verified(
                        8L, List.of("203.0.113.10")));

        assertThatThrownBy(() -> authorizeH5())
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(VoiceErrorCode.VOICE_WEBRTC_REQUIRED));
        verifyNoInteractions(accessSessionService);
    }

    @Test
    void rejectsWhenRefreshSessionWasRevoked() {
        PreAuthAccess access = prepareCurrentPreAuth();
        when(webRtcService.inspect(access, "203.0.113.10"))
                .thenReturn(WebRtcVerificationDecision.verified(
                        7L, List.of("203.0.113.10")));
        when(accessSessionService.validateActiveBinding(
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new SessionAuthenticationException(
                        SessionAuthenticationErrorCode.REFRESH_TOKEN_INVALID,
                        "Refresh session is unavailable.",
                        true));

        assertThatThrownBy(() -> authorizeH5())
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(VoiceErrorCode.VOICE_SESSION_INVALID));
    }

    @Test
    void failsClosedWhenWebRtcStateCannotBeRead() {
        PreAuthAccess access = prepareCurrentPreAuth();
        when(webRtcService.inspect(access, "203.0.113.10"))
                .thenThrow(new IllegalStateException("test infrastructure failure"));

        assertThatThrownBy(() -> authorizeH5())
                .isInstanceOfSatisfying(VoiceException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo(
                                        VoiceErrorCode.VOICE_INFRASTRUCTURE_UNAVAILABLE));
        verifyNoInteractions(accessSessionService);
    }

    private PreAuthAccess prepareCurrentPreAuth() {
        prepareConsumedH5Ticket();
        PreAuthAccess access = verifiedAccess();
        when(blockService.remainingBlockTtlByDigest(id('F'))).thenReturn(Duration.ZERO);
        when(preAuthService.resolveBound(
                RiskScope.USER,
                id('A'),
                id('B'),
                com.example.temperate.service.risk.domain.RiskSessionType.USER_REFRESH,
                id('C'))).thenReturn(Optional.of(access));
        return access;
    }

    private void prepareConsumedH5Ticket() {
        when(ticketService.consume("G".repeat(43))).thenReturn(
                new VoiceSessionTicketSnapshot(
                        2,
                        binding(VoiceClientPlatform.H5),
                        Instant.now().plusSeconds(30)));
    }

    private VoiceHandshakePrincipal authorizeH5() {
        return service.authorize(new VoiceHandshakeCommand(
                "G".repeat(43),
                VoiceClientPlatform.H5,
                true,
                "203.0.113.10"));
    }

    private static PreAuthAccess verifiedAccess() {
        PreAuthAccess access = mock(PreAuthAccess.class);
        PreAuthState state = mock(PreAuthState.class);
        when(access.tokenDigest()).thenReturn(id('A'));
        when(access.state()).thenReturn(state);
        when(state.scope()).thenReturn(RiskScope.USER);
        when(state.webRtcPhase()).thenReturn(PreAuthWebRtcPhase.VERIFIED);
        when(state.webRtcGeneration()).thenReturn(7L);
        when(state.sessionType()).thenReturn(
                com.example.temperate.service.risk.domain.RiskSessionType.USER_REFRESH);
        when(state.sessionRefDigest()).thenReturn(id('C'));
        when(state.deviceDigest()).thenReturn(id('B'));
        return access;
    }

    private static VoiceTicketSecurityBinding binding(VoiceClientPlatform platform) {
        return new VoiceTicketSecurityBinding(
                10001L,
                platform,
                "A".repeat(43),
                "B".repeat(43),
                "C".repeat(43),
                "D".repeat(43),
                "E".repeat(43),
                "F".repeat(43),
                7L);
    }

    private static HmacIdentifier id(char value) {
        return HmacIdentifier.fromProtectedValue(String.valueOf(value).repeat(43));
    }
}
