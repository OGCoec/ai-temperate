package com.example.temperate.service.risk.preauth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.service.risk.preauth.domain.PreAuthRequiredException;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.domain.PreAuthWebRtcPhase;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtectionException;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtector;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证 PreAuth 查询和认证轮换严格遵守会话绑定及 WebRTC 已验证状态。
 */
final class PreAuthServiceImplTest {

    private static final HmacSha256Identifier HMAC = new HmacSha256Identifier(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @Test
    void resolvesOnlyTheCurrentExactlyBoundPreAuthState() {
        PreAuthStore store = mock(PreAuthStore.class);
        PreAuthState state = mock(PreAuthState.class);
        HmacIdentifier token = HMAC.identify("preauth-token");
        HmacIdentifier device = HMAC.identify("device");
        HmacIdentifier session = HMAC.identify("session");
        when(store.find(RiskScope.USER, token)).thenReturn(Optional.of(state));
        when(state.schemaVersion()).thenReturn(PreAuthState.CURRENT_SCHEMA_VERSION);
        when(state.scope()).thenReturn(RiskScope.USER);
        when(state.deviceDigest()).thenReturn(device);
        when(state.sessionType()).thenReturn(RiskSessionType.USER_REFRESH);
        when(state.sessionRefDigest()).thenReturn(session);
        PreAuthServiceImpl service = new PreAuthServiceImpl(
                store,
                mock(NetworkRiskIdentifier.class),
                mock(NetworkRiskProperties.class),
                mock(WebRtcIpProtector.class));

        assertThat(service.resolveBound(
                RiskScope.USER,
                token,
                device,
                RiskSessionType.USER_REFRESH,
                session)).isPresent();
        assertThat(service.resolveBound(
                RiskScope.USER,
                token,
                HMAC.identify("other-device"),
                RiskSessionType.USER_REFRESH,
                session)).isEmpty();
        assertThat(service.resolveBound(
                RiskScope.USER,
                token,
                device,
                RiskSessionType.USER_REFRESH,
                HMAC.identify("other-session"))).isEmpty();
    }

    @Test
    void refusesAuthenticatedPromotionWhenFreshStateIsNotVerified() {
        PreAuthStore store = mock(PreAuthStore.class);
        PreAuthState staleState = mock(PreAuthState.class);
        HmacIdentifier token = HMAC.identify("preauth-token");
        HmacIdentifier device = HMAC.identify("device");
        NetworkRiskIdentifier identifier = new NetworkRiskIdentifier(HMAC);
        when(store.find(RiskScope.USER, token)).thenReturn(Optional.of(staleState));
        when(staleState.schemaVersion()).thenReturn(PreAuthState.CURRENT_SCHEMA_VERSION);
        when(staleState.scope()).thenReturn(RiskScope.USER);
        when(staleState.deviceDigest()).thenReturn(device);
        when(staleState.currentIpDigest()).thenReturn(identifier.identifyIp("8.8.8.8"));
        when(staleState.webRtcPhase()).thenReturn(PreAuthWebRtcPhase.PENDING);
        when(staleState.webRtcGeneration()).thenReturn(3L);
        PreAuthAccess access = new PreAuthAccess(token, staleState);
        PreAuthServiceImpl service = new PreAuthServiceImpl(
                store,
                identifier,
                mock(NetworkRiskProperties.class),
                mock(WebRtcIpProtector.class));

        assertThatThrownBy(() -> service.promoteAuthenticatedAfterWebRtcVerified(
                access,
                RiskSessionType.USER_REFRESH,
                "refresh-token",
                "8.8.8.8",
                java.time.Instant.parse("2026-07-25T12:00:00Z")))
                .isInstanceOf(PreAuthRequiredException.class);
    }

    @Test
    void refusesVerifiedPromotionWhenProtectedWebRtcEvidenceCannotBeRead() {
        PreAuthStore store = mock(PreAuthStore.class);
        WebRtcIpProtector protector = mock(WebRtcIpProtector.class);
        PreAuthState state = mock(PreAuthState.class);
        HmacIdentifier token = HMAC.identify("preauth-token");
        HmacIdentifier device = HMAC.identify("device");
        NetworkRiskIdentifier identifier = new NetworkRiskIdentifier(HMAC);
        HmacIdentifier ip = identifier.identifyIp("8.8.8.8");
        when(store.find(RiskScope.USER, token)).thenReturn(Optional.of(state));
        when(state.schemaVersion()).thenReturn(PreAuthState.CURRENT_SCHEMA_VERSION);
        when(state.scope()).thenReturn(RiskScope.USER);
        when(state.deviceDigest()).thenReturn(device);
        when(state.currentIpDigest()).thenReturn(ip);
        when(state.webRtcPhase()).thenReturn(PreAuthWebRtcPhase.VERIFIED);
        when(state.webRtcGeneration()).thenReturn(3L);
        when(state.webRtcIps()).thenReturn("invalid-protected-value");
        when(protector.decrypt(
                "invalid-protected-value", RiskScope.USER, token, ip))
                .thenThrow(new WebRtcIpProtectionException());
        PreAuthServiceImpl service = new PreAuthServiceImpl(
                store,
                identifier,
                mock(NetworkRiskProperties.class),
                protector);

        assertThatThrownBy(() -> service.promoteAuthenticatedAfterWebRtcVerified(
                new PreAuthAccess(token, state),
                RiskSessionType.USER_REFRESH,
                "refresh-token",
                "8.8.8.8",
                java.time.Instant.parse("2026-07-25T12:00:00Z")))
                .isInstanceOf(PreAuthRequiredException.class);
        verify(store).find(RiskScope.USER, token);
        verifyNoMoreInteractions(store);
    }
}
