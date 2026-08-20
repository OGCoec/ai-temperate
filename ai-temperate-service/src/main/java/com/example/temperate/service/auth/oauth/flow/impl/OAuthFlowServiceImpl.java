package com.example.temperate.service.auth.oauth.flow.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowService;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStartCommand;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStartResult;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowTokenGenerator;
import com.example.temperate.service.auth.oauth.flow.OAuthInteractionMode;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthAuthorizationState;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 编排 OAuth 短时流程启动以及浏览器 state/PKCE/nonce 的创建与一次性消费。
 *
 * <p>H5 原始 Flow Token 只进入 HttpOnly Cookie，Android 原始 Flow Token 只进入受保护响应与 KeyStore；
 * Redis 始终使用 HMAC Key。Android 浏览器跳转额外使用一次性 NanoID32 launch ticket，不把 Flow Token 放入 URL。</p>
 */
@Service
public final class OAuthFlowServiceImpl implements OAuthFlowService {

    private static final Duration IDLE_TTL = Duration.ofMinutes(10);
    private static final Duration ABSOLUTE_TTL = Duration.ofMinutes(30);
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private final OAuthFlowStore flowStore;
    private final OAuthFlowTokenGenerator tokenGenerator;
    private final AuthSessionSecretProtector protector;
    private final Clock clock;

    public OAuthFlowServiceImpl(
            OAuthFlowStore flowStore,
            OAuthFlowTokenGenerator tokenGenerator,
            AuthSessionSecretProtector protector,
            Clock clock) {
        this.flowStore = Objects.requireNonNull(flowStore);
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator);
        this.protector = Objects.requireNonNull(protector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public OAuthFlowStartResult start(OAuthFlowStartCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        validateMode(command);
        String rawFlowToken = tokenGenerator.newFlowToken();
        String rawNonce = command.interactionMode() == OAuthInteractionMode.GOOGLE_NATIVE
                ? tokenGenerator.newNonce() : null;
        ProtectedOAuthFlowAccess access = protect(new OAuthFlowAccess(
                rawFlowToken,
                command.deviceInstallationId(),
                command.canonicalIp()));
        Instant now = clock.instant();
        flowStore.create(
                access,
                command.provider(),
                command.platform(),
                command.interactionMode(),
                rawNonce == null ? null : protector.oauthNonce(rawNonce),
                now);

        String launchTicket = null;
        if (command.platform() == OAuthClientPlatform.ANDROID
                && command.interactionMode() == OAuthInteractionMode.BROWSER) {
            launchTicket = tokenGenerator.newLaunchTicket();
            flowStore.createLaunchTicket(
                    protector.oauthLaunchTicket(launchTicket),
                    access.flowId(),
                    command.provider(),
                    now);
        }
        return new OAuthFlowStartResult(
                rawFlowToken,
                rawNonce,
                launchTicket,
                now.plus(IDLE_TTL),
                now.plus(ABSOLUTE_TTL));
    }

    @Override
    public ProtectedOAuthFlowAccess protect(OAuthFlowAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        try {
            return new ProtectedOAuthFlowAccess(
                    protector.oauthFlowToken(access.rawFlowToken()),
                    protector.device(access.deviceInstallationId()),
                    protector.deviceBlock(access.deviceInstallationId()),
                    protector.oauthClientIp(access.canonicalIp()));
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    OAuthFlowErrorCode.FLOW_FORBIDDEN,
                    "OAuth flow access is invalid.",
                    exception);
        }
    }

    @Override
    public OAuthFlowSnapshot getRequired(OAuthFlowAccess access) {
        return flowStore.getRequired(protect(access), clock.instant());
    }

    @Override
    public OAuthBrowserAuthorization beginBrowserAuthorization(
            HmacIdentifier flowId,
            OAuthProvider provider,
            OAuthClientPlatform platform,
            String rawBrowserBinding,
            String redirectUri) {
        String rawState = tokenGenerator.newState();
        String verifier = tokenGenerator.newPkceVerifier();
        String rawNonce = provider == OAuthProvider.GOOGLE
                ? tokenGenerator.newNonce() : null;
        ProtectedOAuthAuthorizationState protectedState;
        try {
            protectedState = new ProtectedOAuthAuthorizationState(
                    protector.oauthState(rawState),
                    protector.oauthBrowserBinding(rawBrowserBinding));
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    OAuthFlowErrorCode.FLOW_FORBIDDEN,
                    "OAuth browser binding is invalid.",
                    exception);
        }
        flowStore.createAuthorizationState(
                protectedState,
                Objects.requireNonNull(flowId),
                Objects.requireNonNull(provider),
                Objects.requireNonNull(platform),
                verifier,
                rawNonce == null ? null : protector.oauthNonce(rawNonce),
                redirectUri,
                clock.instant());
        return new OAuthBrowserAuthorization(
                rawState,
                pkceChallenge(verifier),
                rawNonce,
                flowId);
    }

    @Override
    public OAuthAuthorizationStateSnapshot consumeBrowserAuthorization(
            String rawState,
            String rawBrowserBinding,
            OAuthProvider provider) {
        try {
            return flowStore.consumeAuthorizationState(
                    new ProtectedOAuthAuthorizationState(
                            protector.oauthState(rawState),
                            protector.oauthBrowserBinding(rawBrowserBinding)),
                    provider,
                    clock.instant());
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    OAuthFlowErrorCode.STATE_REJECTED,
                    "OAuth browser state is invalid.",
                    exception);
        }
    }

    @Override
    public HmacIdentifier protectFlowToken(String rawFlowToken) {
        try {
            return protector.oauthFlowToken(rawFlowToken);
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    OAuthFlowErrorCode.FLOW_FORBIDDEN,
                    "OAuth flow token is invalid.",
                    exception);
        }
    }

    @Override
    public HmacIdentifier consumeLaunchTicket(
            String rawLaunchTicket, OAuthProvider provider) {
        try {
            return flowStore.consumeLaunchTicket(
                    protector.oauthLaunchTicket(rawLaunchTicket),
                    provider,
                    clock.instant());
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    OAuthFlowErrorCode.STATE_REJECTED,
                    "OAuth launch ticket is invalid.",
                    exception);
        }
    }

    @Override
    public void consumeNativeNonce(OAuthFlowAccess access, String rawNonce) {
        try {
            flowStore.consumeNativeNonce(
                    protect(access),
                    protector.oauthNonce(rawNonce),
                    clock.instant());
        } catch (IllegalArgumentException exception) {
            throw rejected(
                    OAuthFlowErrorCode.NONCE_REJECTED,
                    "OAuth native nonce is invalid.",
                    exception);
        }
    }

    @Override
    public String newBrowserBinding() {
        return tokenGenerator.newBrowserBinding();
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return BASE64_URL.encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static void validateMode(OAuthFlowStartCommand command) {
        Objects.requireNonNull(command.provider());
        Objects.requireNonNull(command.platform());
        Objects.requireNonNull(command.interactionMode());
        boolean nativeGoogle = command.provider() == OAuthProvider.GOOGLE
                && command.platform() == OAuthClientPlatform.ANDROID
                && command.interactionMode() == OAuthInteractionMode.GOOGLE_NATIVE;
		// 安卓 Google 的受众校验依赖 Credential Manager 返回的 nonce 与 ID Token，禁止降级到浏览器 Flow。
		if (command.provider() == OAuthProvider.GOOGLE
				&& command.platform() == OAuthClientPlatform.ANDROID
				&& command.interactionMode() == OAuthInteractionMode.BROWSER) {
			throw new IllegalArgumentException(
					"Android Google OAuth requires native Credential Manager.");
		}
        if (command.interactionMode() == OAuthInteractionMode.GOOGLE_NATIVE && !nativeGoogle) {
            throw new IllegalArgumentException(
                    "Native OAuth is only supported for Google on Android.");
        }
    }

    private static OAuthFlowException rejected(
            OAuthFlowErrorCode code, String message, IllegalArgumentException cause) {
        return new OAuthFlowException(code, message, cause);
    }
}
