package com.example.temperate.service.auth.oauth.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.flow.impl.OAuthFlowServiceImpl;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 H5、Android 原生 Google 和 Android 浏览器 OAuth 启动材料严格分离。
 */
class OAuthFlowServiceImplTest {

    private OAuthFlowStore store;
    private OAuthFlowTokenGenerator generator;
    private AuthSessionSecretProtector protector;
    private OAuthFlowService service;

    @BeforeEach
    void setUp() {
        store = mock(OAuthFlowStore.class);
        generator = mock(OAuthFlowTokenGenerator.class);
        protector = mock(AuthSessionSecretProtector.class);
        when(generator.newFlowToken()).thenReturn("f".repeat(38));
        when(generator.newNonce()).thenReturn("n".repeat(43));
        when(generator.newLaunchTicket()).thenReturn("l".repeat(32));
        when(protector.oauthFlowToken(any())).thenReturn(id('f'));
        when(protector.device(any())).thenReturn(id('d'));
        when(protector.deviceBlock(any())).thenReturn(id('b'));
        when(protector.oauthClientIp(any())).thenReturn(id('i'));
        when(protector.oauthNonce(any())).thenReturn(id('n'));
        when(protector.oauthLaunchTicket(any())).thenReturn(id('l'));
        service = new OAuthFlowServiceImpl(
                store,
                generator,
                protector,
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void androidNativeGoogleReturnsNonceWithoutLaunchTicket() {
        OAuthFlowStartResult result = service.start(new OAuthFlowStartCommand(
                OAuthProvider.GOOGLE,
                OAuthClientPlatform.ANDROID,
                OAuthInteractionMode.GOOGLE_NATIVE,
                "123e4567-e89b-42d3-a456-426614174000",
                "203.0.113.8"));

        assertNotNull(result.rawFlowToken());
        assertNotNull(result.nonce());
        assertNull(result.launchTicket());
        verify(store).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void androidBrowserFlowReturnsOneTimeLaunchTicket() {
        OAuthFlowStartResult result = service.start(new OAuthFlowStartCommand(
                OAuthProvider.GITHUB,
                OAuthClientPlatform.ANDROID,
                OAuthInteractionMode.BROWSER,
                "123e4567-e89b-42d3-a456-426614174000",
                "203.0.113.8"));

        assertEquals("l".repeat(32), result.launchTicket());
        assertNull(result.nonce());
        verify(store).createLaunchTicket(any(), any(), any(), any());
    }

    @Test
    void androidGoogleBrowserDowngradeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.start(new OAuthFlowStartCommand(
                OAuthProvider.GOOGLE,
                OAuthClientPlatform.ANDROID,
                OAuthInteractionMode.BROWSER,
                "123e4567-e89b-42d3-a456-426614174000",
                "203.0.113.8")));
    }

    private static HmacIdentifier id(char value) {
        return HmacIdentifier.fromProtectedValue(String.valueOf(value).repeat(43));
    }
}
