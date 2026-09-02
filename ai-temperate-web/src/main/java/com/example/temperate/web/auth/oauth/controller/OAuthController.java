package com.example.temperate.web.auth.oauth.controller;

import com.example.temperate.common.net.ip.IpAddressIdentity;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.validation.phone.PhoneNumberInputPolicy;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.oauth.completion.OAuthLoginCompletionService;
import com.example.temperate.service.auth.oauth.domain.OAuthProvider;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthAuthorizationStateSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthBrowserAuthorization;
import com.example.temperate.service.auth.oauth.flow.OAuthClientPlatform;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowService;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowState;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStartCommand;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStartResult;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.flow.OAuthInteractionMode;
import com.example.temperate.service.auth.oauth.identity.OAuthProviderCompletionService;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneAccess;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneFlowService;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneStartCommand;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneStartResult;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcAttemptService;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcAttemptService.ResumeResult;
import com.example.temperate.service.auth.oauth.webrtc.OAuthWebRtcAttemptService.SuspendResult;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.risk.config.NetworkRiskProperties;
import com.example.temperate.service.risk.domain.TrustedNetworkObservation;
import com.example.temperate.service.risk.preauth.domain.PreAuthAccess;
import com.example.temperate.web.auth.api.WebInvalidInputException;
import com.example.temperate.web.auth.oauth.config.OAuthClientProperties;
import com.example.temperate.web.auth.oauth.diagnostic.OAuthCallbackFailureLogger;
import com.example.temperate.web.auth.oauth.nativegoogle.GoogleNativeIdentityVerifier;
import com.example.temperate.web.auth.oauth.nativegoogle.VerifiedGoogleNativeIdentity;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderClientRegistry;
import com.example.temperate.web.auth.oauth.provider.OAuthProviderClientStrategy;
import com.example.temperate.web.auth.oauth.transport.OAuthFlowCookieWriter;
import com.example.temperate.web.auth.oauth.transport.OAuthFlowCookieWriter.OAuthH5Cookies;
import com.example.temperate.web.auth.oauth.transport.OAuthLoginResultTransport;
import com.example.temperate.web.auth.oauth.transport.OAuthLoginResultTransport.OAuthLoginResponse;
import com.example.temperate.web.auth.session.transport.AuthClientPlatform;
import com.example.temperate.web.risk.RiskRequestContextResolver;
import com.example.temperate.web.risk.NetworkRiskInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * 提供 H5 与 Android 共用的 Google/GitHub OAuth 启动、回调、补手机号和最终登录接口。
 *
 * <p>浏览器 Provider 回调只接受一次性 state、PKCE、Google nonce 与浏览器握手 Cookie，不要求 CSRF Header；
 * Android 原生 Google 只接受 Credential Manager ID Token、一次性 nonce 和绑定设备的 Flow Token。该控制器
 * 不允许客户端指定回调或返回地址，也不在 URL、日志或长期存储中放置 Provider Token 与个人信息。</p>
 */
@RestController
@RequestMapping("/api/auth/oauth2")
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
@Tag(
        name = "认证-第三方登录",
        description = "提供 H5 与 Android 的 Google、GitHub 登录，覆盖浏览器授权、Android 原生 Google、"
                + "手机号补验与最终会话签发；回调只使用固定 HTTPS 地址，不负责第三方解绑或设置密码。")
public final class OAuthController {

    private static final String DEVICE_HEADER = "X-Device-Installation-Id";
    private static final String PLATFORM_HEADER = "X-Client-Platform";
    private static final String FLOW_HEADER = "X-OAuth-Flow-Token";
    private static final String PHONE_FLOW_HEADER = "X-OAuth-Phone-Flow-Token";
    private static final String CHALLENGE_HEADER = "X-Turnstile-Challenge";
    private static final String WEBRTC_ATTEMPT_HEADER = "X-AIT-OAuth-WebRTC-Attempt-Id";
    private static final String WEBRTC_GENERATION_HEADER = "X-AIT-WebRTC-Probe-Generation";

    private final OAuthFlowService flowService;
    private final OAuthFlowStore flowStore;
    private final OAuthProviderClientRegistry providerRegistry;
    private final OAuthProviderCompletionService providerCompletionService;
    private final GoogleNativeIdentityVerifier nativeIdentityVerifier;
    private final OAuthPhoneFlowService phoneFlowService;
    private final OAuthLoginCompletionService loginCompletionService;
    private final OAuthLoginResultTransport loginResultTransport;
    private final OAuthFlowCookieWriter cookieWriter;
    private final OAuthCallbackFailureLogger callbackFailureLogger;
    private final OAuthClientProperties properties;
    private final RiskRequestContextResolver riskContextResolver;
    private final Clock clock;
    private final OAuthWebRtcAttemptService oauthWebRtcAttemptService;
    private final NetworkRiskProperties networkRiskProperties;

    public OAuthController(
            OAuthFlowService flowService,
            OAuthFlowStore flowStore,
            OAuthProviderClientRegistry providerRegistry,
            OAuthProviderCompletionService providerCompletionService,
            GoogleNativeIdentityVerifier nativeIdentityVerifier,
            OAuthPhoneFlowService phoneFlowService,
            OAuthLoginCompletionService loginCompletionService,
            OAuthLoginResultTransport loginResultTransport,
            OAuthFlowCookieWriter cookieWriter,
            OAuthCallbackFailureLogger callbackFailureLogger,
            OAuthClientProperties properties,
            RiskRequestContextResolver riskContextResolver,
            Clock clock,
            OAuthWebRtcAttemptService oauthWebRtcAttemptService,
            NetworkRiskProperties networkRiskProperties) {
        this.flowService = Objects.requireNonNull(flowService);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.providerRegistry = Objects.requireNonNull(providerRegistry);
        this.providerCompletionService = Objects.requireNonNull(providerCompletionService);
        this.nativeIdentityVerifier = Objects.requireNonNull(nativeIdentityVerifier);
        this.phoneFlowService = Objects.requireNonNull(phoneFlowService);
        this.loginCompletionService = Objects.requireNonNull(loginCompletionService);
        this.loginResultTransport = Objects.requireNonNull(loginResultTransport);
        this.cookieWriter = Objects.requireNonNull(cookieWriter);
        this.callbackFailureLogger = Objects.requireNonNull(callbackFailureLogger);
        this.properties = Objects.requireNonNull(properties);
        this.riskContextResolver = Objects.requireNonNull(riskContextResolver);
        this.clock = Objects.requireNonNull(clock);
        this.oauthWebRtcAttemptService = Objects.requireNonNull(oauthWebRtcAttemptService);
        this.networkRiskProperties = Objects.requireNonNull(networkRiskProperties);
    }

    @PostMapping("/start")
    @Operation(summary = "启动 Google 或 GitHub OAuth 流程")
    public OAuthStartResponse start(
            @Valid @RequestBody OAuthStartRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform transportPlatform = AuthClientPlatform.fromHeader(platformHeader);
        OAuthClientPlatform platform = transportPlatform == AuthClientPlatform.ANDROID
                ? OAuthClientPlatform.ANDROID : OAuthClientPlatform.H5;
        OAuthInteractionMode mode = interactionMode(body, platform);
        if ((body.probeGeneration() == null) != (body.probeRunId() == null)) {
            // generation 与 probeRunId 是同一次 start 的不可拆分关联，禁止残缺输入创建孤立 OAuth Flow。
            throw new WebInvalidInputException();
        }
        OAuthFlowStartResult result = flowService.start(new OAuthFlowStartCommand(
                body.provider(), platform, mode, deviceId, canonicalIp(request)));
        SuspendResult webRtcAttempt = null;
        if (platform == OAuthClientPlatform.H5
                && body.probeGeneration() != null
                && body.probeRunId() != null) {
            // Flow 已落库后才绑定现有 generation，确保暂停记录总能指向一个真实且同设备的 OAuth Flow。
            webRtcAttempt = oauthWebRtcAttemptService.suspend(
                    flowService.protect(new OAuthFlowAccess(
                            result.rawFlowToken(), deviceId, canonicalIp(request))),
                    requirePreAuth(request),
                    body.probeGeneration(),
                    body.probeRunId(),
                    result.absoluteExpiresAt());
        }
        URI authorizationUrl = null;
        if (mode == OAuthInteractionMode.BROWSER) {
            authorizationUrl = authorizationEntry(body.provider(), result.launchTicket());
        }
        if (platform == OAuthClientPlatform.H5) {
            String browserBinding = flowService.newBrowserBinding();
            cookieWriter.writeH5Start(
                    response, result.rawFlowToken(), browserBinding);
        }
        noStore(response);
        boolean android = platform == OAuthClientPlatform.ANDROID;
        return new OAuthStartResponse(
                mode == OAuthInteractionMode.BROWSER
                        ? "BROWSER_REDIRECT" : "GOOGLE_NATIVE",
                authorizationUrl,
                android ? result.rawFlowToken() : null,
                mode == OAuthInteractionMode.GOOGLE_NATIVE ? result.nonce() : null,
                mode == OAuthInteractionMode.GOOGLE_NATIVE
                        ? properties.google().androidServerClientId() : null,
                result.expiresAt(),
                result.absoluteExpiresAt(),
                webRtcAttempt == null ? null : webRtcAttempt.state().name(),
                webRtcAttempt == null ? null : webRtcAttempt.attemptId(),
                webRtcAttempt == null ? null : webRtcAttempt.probeGeneration(),
                webRtcAttempt != null && webRtcAttempt.fallbackUsed());
    }

    @GetMapping("/authorization/{provider}")
    @Operation(summary = "进入固定 Provider 授权页面")
    public ResponseEntity<Void> authorize(
            @PathVariable String provider,
            @RequestParam(value = "launch", required = false) String launchTicket,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuthProvider selected = provider(provider);
        OAuthH5Cookies cookies = cookieWriter.read(request);
        final HmacIdentifier flowId;
        final OAuthClientPlatform platform;
        final String browserBinding;
        if (launchTicket != null && !launchTicket.isBlank()) {
            flowId = flowService.consumeLaunchTicket(launchTicket, selected);
            platform = OAuthClientPlatform.ANDROID;
            browserBinding = flowService.newBrowserBinding();
            cookieWriter.writeBrowserHandshake(response, browserBinding);
        } else {
            flowId = flowService.protectFlowToken(cookies.rawFlowToken());
            platform = OAuthClientPlatform.H5;
            browserBinding = requireText(cookies.rawBrowserBinding(), "OAuth handshake is missing.");
        }
        URI callback = properties.callbackUri(selected);
        OAuthBrowserAuthorization authorization = flowService.beginBrowserAuthorization(
                flowId, selected, platform, browserBinding, callback.toString());
        OAuthProviderClientStrategy strategy = providerRegistry.getRequired(selected);
        URI location = strategy.authorizationUri(authorization, callback);
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(location)
                .build();
    }

    @GetMapping("/code/{provider}")
    @Operation(summary = "消费 Provider 授权回调")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String providerError,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuthProvider selected = provider(provider);
        OAuthAuthorizationStateSnapshot authorizationState = null;
        boolean statePresent = state != null && !state.isBlank();
        boolean handshakePresent = false;
        try {
            String binding = cookieWriter.read(request).rawBrowserBinding();
            handshakePresent = binding != null && !binding.isBlank();
            authorizationState = flowService.consumeBrowserAuthorization(
                    requireText(state, "OAuth state is missing."),
                    requireText(binding, "OAuth handshake is missing."),
                    selected);
            if (providerError != null || code == null || code.isBlank()) {
                flowStore.markFailed(authorizationState.flowId(), clock.instant());
                callbackFailureLogger.logAuthorizationRejected(
                        selected, authorizationState.platform());
            } else {
                TrustedOAuthIdentity identity = providerRegistry.getRequired(selected)
                        .exchange(code, authorizationState);
                providerCompletionService.accept(authorizationState.flowId(), identity);
            }
            return fixedReturn(authorizationState.platform());
        } catch (RuntimeException exception) {
            if (authorizationState != null) {
                try {
                    flowStore.markFailed(authorizationState.flowId(), clock.instant());
                } catch (RuntimeException stateFailure) {
                    exception.addSuppressed(stateFailure);
                }
            }
            OAuthClientPlatform platform = authorizationState == null
                    ? OAuthClientPlatform.H5 : authorizationState.platform();
            callbackFailureLogger.logFailure(
                    selected,
                    platform,
                    exception,
                    authorizationState != null,
                    statePresent,
                    handshakePresent);
            return fixedReturn(platform);
        } finally {
            cookieWriter.clearHandshake(response);
            noStore(response);
        }
    }

    @PostMapping("/google/native/complete")
    @Operation(summary = "验证 Android 原生 Google ID Token")
    public OAuthStatusResponse completeNativeGoogle(
            @Valid @RequestBody NativeGoogleRequest body,
            @RequestHeader(FLOW_HEADER) String rawFlowToken,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuthFlowAccess access = access(rawFlowToken, deviceId, request);
        OAuthFlowSnapshot current = flowService.getRequired(access);
        if (current.provider() != OAuthProvider.GOOGLE
                || current.platform() != OAuthClientPlatform.ANDROID
                || current.interactionMode() != OAuthInteractionMode.GOOGLE_NATIVE) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.INVALID_TRANSITION,
                    "OAuth flow is not native Google.");
        }
        VerifiedGoogleNativeIdentity verified = nativeIdentityVerifier.verify(body.idToken());
        // 签名与声明验证完成后立即一次性消费 nonce，再允许可信身份进入本地账号解析。
        flowService.consumeNativeNonce(access, verified.rawNonce());
        providerCompletionService.accept(
                flowService.protectFlowToken(rawFlowToken), verified.identity());
        noStore(response);
        return status(flowService.getRequired(access));
    }

    @GetMapping("/flow/status")
    @Operation(summary = "查询 OAuth Flow 当前状态")
    public OAuthStatusResponse status(
            @RequestHeader(value = FLOW_HEADER, required = false) String androidFlowToken,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuthFlowAccess access = oauthAccess(
                androidFlowToken, deviceId, platformHeader, request);
        noStore(response);
        return status(flowService.getRequired(access));
    }

    @PostMapping("/webrtc/resume")
    @Operation(summary = "恢复 H5 OAuth 跳转前暂停的 WebRTC attempt")
    public OAuthWebRtcResumeResponse resumeWebRtc(
            @Valid @RequestBody OAuthWebRtcResumeRequest body,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (AuthClientPlatform.fromHeader(platformHeader) != AuthClientPlatform.H5) {
            throw new WebInvalidInputException();
        }
        OAuthFlowAccess access = oauthAccess(null, deviceId, platformHeader, request);
        OAuthFlowSnapshot snapshot = flowService.getRequired(access);
        if (snapshot.platform() != OAuthClientPlatform.H5
                || snapshot.state() != OAuthFlowState.READY_TO_COMPLETE) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.INVALID_TRANSITION,
                    "OAuth flow is not ready for WebRTC resume.");
        }
        ResumeResult resumed = oauthWebRtcAttemptService.resume(
                flowService.protect(access),
                requirePreAuth(request),
                body.attemptId(),
                body.probeGeneration());
        noStore(response);
        return new OAuthWebRtcResumeResponse(
                resumed.state().name(),
                resumed.attemptId(),
                resumed.probeGeneration(),
                networkRiskProperties.webRtc().stunUrls().stream()
                        .map(Object::toString)
                        .toList(),
                networkRiskProperties.webRtc().probeTimeout().toMillis(),
                networkRiskProperties.webRtc().reportGrace().toMillis(),
                "/api/_edge/webrtc/report",
                resumed.fallbackUsed());
    }

    @PostMapping("/phone/start")
    @Operation(summary = "选择并锁定 OAuth 待验证手机号")
    public OAuthPhoneStartResponse startPhone(
            @Valid @RequestBody PhoneStartRequest body,
            @RequestHeader(value = FLOW_HEADER, required = false) String androidFlowToken,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        OAuthFlowAccess oauthAccess = oauthAccess(
                androidFlowToken, deviceId, platformHeader, request);
        OAuthPhoneStartResult result = phoneFlowService.start(new OAuthPhoneStartCommand(
                oauthAccess, body.countryIso2(), body.phoneNumber()));
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.writePhoneFlow(
                    response, result.rawPhoneFlowToken(), result.challengeHandle());
        }
        noStore(response);
        boolean android = platform == AuthClientPlatform.ANDROID;
        return new OAuthPhoneStartResponse(
                android ? result.rawPhoneFlowToken() : null,
                result.challengeHandle(),
                result.expiresAt());
    }

    @PostMapping("/phone/turnstile")
    @Operation(summary = "验证 OAuth 手机流程的 Turnstile")
    public Mono<AcceptedResponse> verifyPhoneTurnstile(
            @Valid @RequestBody TurnstileRequest body,
            @RequestHeader(value = FLOW_HEADER, required = false) String oauthToken,
            @RequestHeader(value = PHONE_FLOW_HEADER, required = false) String phoneFlowToken,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        return phoneFlowService.verifyTurnstile(
                        phoneAccess(oauthToken, phoneFlowToken, challenge,
                                deviceId, platformHeader, request),
                        body.turnstileToken())
                .thenReturn(new AcceptedResponse(true));
    }

    @PostMapping("/phone/send")
    @Operation(summary = "发送 OAuth 手机验证码")
    public AcceptedResponse sendPhoneCode(
            @Valid @RequestBody PhoneSendRequest body,
            @RequestHeader(value = FLOW_HEADER, required = false) String oauthToken,
            @RequestHeader(value = PHONE_FLOW_HEADER, required = false) String phoneFlowToken,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request) {
        phoneFlowService.send(
                phoneAccess(oauthToken, phoneFlowToken, challenge,
                        deviceId, platformHeader, request),
                body.deliveryMethod());
        return new AcceptedResponse(true);
    }

    @PostMapping("/phone/verify")
    @Operation(summary = "验证并一次性消费 OAuth 手机验证码")
    public OAuthStatusResponse verifyPhoneCode(
            @Valid @RequestBody PhoneVerifyRequest body,
            @RequestHeader(value = FLOW_HEADER, required = false) String oauthToken,
            @RequestHeader(value = PHONE_FLOW_HEADER, required = false) String phoneFlowToken,
            @RequestHeader(value = CHALLENGE_HEADER, required = false) String challenge,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuthPhoneAccess access = phoneAccess(
                oauthToken, phoneFlowToken, challenge,
                deviceId, platformHeader, request);
        phoneFlowService.verify(access, body.code());
        noStore(response);
        return status(flowService.getRequired(access.oauthAccess()));
    }

    @PostMapping("/complete")
    @Operation(summary = "完成 OAuth 登录并签发会话或 TOTP 挑战")
    public OAuthLoginResponse complete(
            @RequestHeader(value = FLOW_HEADER, required = false) String androidFlowToken,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            @RequestHeader(value = WEBRTC_ATTEMPT_HEADER, required = false)
                    String webRtcAttemptId,
            @RequestHeader(value = WEBRTC_GENERATION_HEADER, required = false)
                    String webRtcGeneration,
            HttpServletRequest request,
            HttpServletResponse response) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        validateWebRtcAttemptHeaders(platform, webRtcAttemptId, webRtcGeneration);
        OAuthFlowAccess access = oauthAccess(
                androidFlowToken, deviceId, platformHeader, request);
        var protectedAccess = flowService.protect(access);
        LoginResult result = loginCompletionService.complete(access);
        OAuthLoginResponse transported = loginResultTransport.write(
                result,
                platform,
                protectedAccess,
                webRtcAttemptId,
                webRtcGeneration,
                deviceId,
                request,
                response);
        if (platform == AuthClientPlatform.H5) {
            cookieWriter.clearAll(response);
        }
        return transported;
    }

    private static void validateWebRtcAttemptHeaders(
            AuthClientPlatform platform,
            String attemptId,
            String generation) {
        boolean attemptPresent = attemptId != null && !attemptId.isBlank();
        boolean generationPresent = generation != null && !generation.isBlank();
        // 两个字段共同标识同一服务端状态机；必须在消费 OAuth Flow 和创建 Refresh Session 前拒绝残缺输入。
        if (attemptPresent != generationPresent
                || (attemptPresent && platform != AuthClientPlatform.H5)
                || (attemptPresent
                        && (!attemptId.matches(
                                "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                                || !generation.matches("^[1-9][0-9]{0,18}$")))) {
            throw new WebInvalidInputException();
        }
    }

    @PostMapping("/cancel")
    @Operation(summary = "取消并删除 OAuth Flow")
    public AcceptedResponse cancel(
            @RequestHeader(value = FLOW_HEADER, required = false) String androidFlowToken,
            @RequestHeader(DEVICE_HEADER) String deviceId,
            @RequestHeader(value = PLATFORM_HEADER, required = false) String platformHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuthFlowAccess access = oauthAccess(
                androidFlowToken, deviceId, platformHeader, request);
        flowStore.delete(flowService.protect(access).flowId());
        if (AuthClientPlatform.fromHeader(platformHeader) == AuthClientPlatform.H5) {
            cookieWriter.clearAll(response);
        }
        noStore(response);
        return new AcceptedResponse(true);
    }

    private OAuthPhoneAccess phoneAccess(
            String androidOAuthToken,
            String androidPhoneFlowToken,
            String androidChallenge,
            String deviceId,
            String platformHeader,
            HttpServletRequest request) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        OAuthH5Cookies cookies = cookieWriter.read(request);
        String rawPhoneFlowToken = platform == AuthClientPlatform.ANDROID
                ? androidPhoneFlowToken : cookies.rawPhoneFlowToken();
        String challengeHandle = platform == AuthClientPlatform.ANDROID
                ? androidChallenge : cookies.phoneChallengeHandle();
        return new OAuthPhoneAccess(
                oauthAccess(androidOAuthToken, deviceId, platformHeader, request),
                requireText(rawPhoneFlowToken, "OAuth phone flow token is missing."),
                requireText(challengeHandle, "OAuth phone challenge is missing."));
    }

    private OAuthFlowAccess oauthAccess(
            String androidFlowToken,
            String deviceId,
            String platformHeader,
            HttpServletRequest request) {
        AuthClientPlatform platform = AuthClientPlatform.fromHeader(platformHeader);
        String rawFlowToken = platform == AuthClientPlatform.ANDROID
                ? androidFlowToken : cookieWriter.read(request).rawFlowToken();
        return access(requireText(rawFlowToken, "OAuth flow token is missing."),
                deviceId, request);
    }

    private OAuthFlowAccess access(
            String rawFlowToken,
            String deviceId,
            HttpServletRequest request) {
        return new OAuthFlowAccess(
                rawFlowToken,
                requireText(deviceId, "Device installation id is missing."),
                canonicalIp(request));
    }

    private String canonicalIp(HttpServletRequest request) {
        return riskContextResolver.resolve(request)
                .map(TrustedNetworkObservation::clientIp)
                .orElseGet(() -> IpAddressIdentity.parse(request.getRemoteAddr())
                        .canonicalText());
    }

    private URI authorizationEntry(OAuthProvider provider, String launchTicket) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUri(properties.publicBaseUrl())
                .path("/api/auth/oauth2/authorization/")
                .path(provider.name().toLowerCase(Locale.ROOT));
        if (launchTicket != null) {
            builder.queryParam("launch", launchTicket);
        }
        return builder.build().encode().toUri();
    }

    private ResponseEntity<Void> fixedReturn(OAuthClientPlatform platform) {
        URI location = platform == OAuthClientPlatform.ANDROID
                ? properties.androidReturnUrl() : properties.h5ReturnUrl();
        return ResponseEntity.status(HttpStatus.FOUND)
                .cacheControl(CacheControl.noStore())
                .location(location)
                .build();
    }

    private static OAuthInteractionMode interactionMode(
            OAuthStartRequest request, OAuthClientPlatform platform) {
        if (platform == OAuthClientPlatform.ANDROID
                && request.provider() == OAuthProvider.GOOGLE) {
            return OAuthInteractionMode.GOOGLE_NATIVE;
        }
        return OAuthInteractionMode.BROWSER;
    }

    private static OAuthProvider provider(String value) {
        try {
            return OAuthProvider.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new WebInvalidInputException();
        }
    }

    private static OAuthStatusResponse status(OAuthFlowSnapshot snapshot) {
        return new OAuthStatusResponse(
                snapshot.provider(),
                snapshot.state(),
                snapshot.phoneRequired(),
                snapshot.expiresAt(),
                snapshot.absoluteExpiresAt());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new OAuthFlowException(OAuthFlowErrorCode.FLOW_FORBIDDEN, message);
        }
        return value;
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store, private");
        response.setHeader("Pragma", "no-cache");
    }

    private static PreAuthAccess requirePreAuth(HttpServletRequest request) {
        Object value = request.getAttribute(NetworkRiskInterceptor.PREAUTH_ACCESS_ATTRIBUTE);
        if (value instanceof PreAuthAccess access) {
            return access;
        }
        throw new OAuthFlowException(
                OAuthFlowErrorCode.FLOW_FORBIDDEN,
                "OAuth WebRTC PreAuth is missing.");
    }

    public record OAuthStartRequest(
            @NotNull OAuthProvider provider,
            OAuthInteractionMode interactionMode,
            @Pattern(regexp = "^[1-9][0-9]{0,18}$") String probeGeneration,
            @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                    String probeRunId) {

        public OAuthStartRequest(
                OAuthProvider provider,
                OAuthInteractionMode interactionMode) {
            this(provider, interactionMode, null, null);
        }
    }

    public record OAuthStartResponse(
            String mode,
            URI authorizationUrl,
            String oauthFlowToken,
            String nonce,
            String googleServerClientId,
            Instant expiresAt,
            Instant absoluteExpiresAt,
            String webRtcAttemptState,
            String webRtcAttemptId,
            String probeGeneration,
            boolean fallbackUsed) {
    }

    /** OAuth 回调恢复请求只携带公开 UUID 与 generation，不携带候选、Cookie 或 OAuth state。 */
    public record OAuthWebRtcResumeRequest(
            @NotBlank
            @Pattern(regexp = "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                    String attemptId,
            @NotBlank @Pattern(regexp = "^[1-9][0-9]{0,18}$") String probeGeneration) {
    }

    /** 返回恢复后的同一 attempt、固定 STUN 配置和是否已消耗唯一 fallback。 */
    public record OAuthWebRtcResumeResponse(
            String state,
            String attemptId,
            String probeGeneration,
            java.util.List<String> stunUrls,
            long timeoutMillis,
            long reportGraceMillis,
            String reportPath,
            boolean fallbackUsed) {
    }

    public record NativeGoogleRequest(
            @NotBlank @Size(max = 16_384) String idToken) {
    }

    public record PhoneStartRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryIso2,
            @NotBlank @Pattern(regexp = PhoneNumberInputPolicy.BASIC_PHONE_PATTERN)
                    String phoneNumber) {
    }

    public record TurnstileRequest(
            @NotBlank @Size(max = 4096) String turnstileToken) {
    }

    public record PhoneSendRequest(@NotNull VerificationDeliveryMethod deliveryMethod) {
    }

    public record PhoneVerifyRequest(
            @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
    }

    public record OAuthPhoneStartResponse(
            String phoneFlowToken,
            String turnstileChallenge,
            Instant expiresAt) {
    }

    public record OAuthStatusResponse(
            OAuthProvider provider,
            com.example.temperate.service.auth.oauth.flow.OAuthFlowState state,
            boolean phoneRequired,
            Instant expiresAt,
            Instant absoluteExpiresAt) {
    }

    public record AcceptedResponse(boolean accepted) {
    }
}
