package com.example.temperate.service.risk.preauth.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.RiskScope;
import com.example.temperate.service.risk.domain.RiskSessionType;
import com.example.temperate.service.risk.preauth.domain.PreAuthState;
import com.example.temperate.service.risk.preauth.store.PreAuthStore;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtector;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 验证 Voice 握手使用的 PreAuth 摘要查询必须同时命中 Scope、设备与登录会话绑定。
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
}
