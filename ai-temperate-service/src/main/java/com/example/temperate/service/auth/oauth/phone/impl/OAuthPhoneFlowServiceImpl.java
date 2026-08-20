package com.example.temperate.service.auth.oauth.phone.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.OAuthPhoneCodeStartCommand;
import com.example.temperate.service.auth.login.code.dto.OAuthPhoneCodeStartResult;
import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowState;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowService;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneAccess;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneFlowService;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneRiskService;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneStartCommand;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneStartResult;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 把现有手机验证码登录状态机作为 OAuth 补手机号子流程复用，并用外层 OAuth Flow 锁定手机号与子流程凭据。
 *
 * <p>客户端不能提交 purpose；该实现固定创建 OAUTH_PHONE。更换手机号会创建新的验证码 Flow 和 Challenge，
 * 外层 Lua 原子替换绑定，从而使旧 Turnstile 状态和旧验证码无法再推进 OAuth 登录。</p>
 */
@Service
public final class OAuthPhoneFlowServiceImpl implements OAuthPhoneFlowService {

    private final OAuthFlowService oauthFlowService;
    private final OAuthFlowStore oauthFlowStore;
    private final LoginCodeFlowService loginCodeFlowService;
    private final OAuthPhoneRiskService riskService;
    private final AuthSessionSecretProtector protector;
    private final Clock clock;

    public OAuthPhoneFlowServiceImpl(
            OAuthFlowService oauthFlowService,
            OAuthFlowStore oauthFlowStore,
            LoginCodeFlowService loginCodeFlowService,
            OAuthPhoneRiskService riskService,
            AuthSessionSecretProtector protector,
            Clock clock) {
        this.oauthFlowService = Objects.requireNonNull(oauthFlowService);
        this.oauthFlowStore = Objects.requireNonNull(oauthFlowStore);
        this.loginCodeFlowService = Objects.requireNonNull(loginCodeFlowService);
        this.riskService = Objects.requireNonNull(riskService);
        this.protector = Objects.requireNonNull(protector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public OAuthPhoneStartResult start(OAuthPhoneStartCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        OAuthFlowSnapshot flow = oauthFlowService.getRequired(command.oauthAccess());
        if (flow.trustedIdentity() == null
                || !(flow.state() == OAuthFlowState.PHONE_REQUIRED
                || flow.state() == OAuthFlowState.HUMAN_VERIFICATION_REQUIRED
                || flow.state() == OAuthFlowState.CODE_READY)) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.INVALID_TRANSITION,
                    "OAuth flow does not require a phone.");
        }
        OAuthPhoneCodeStartResult phoneFlow = loginCodeFlowService.startOAuthPhone(
                new OAuthPhoneCodeStartCommand(
                        command.countryIso2(),
                        command.phoneNumber(),
                        command.oauthAccess().deviceInstallationId(),
                        command.oauthAccess().canonicalIp()));
        ProtectedOAuthFlowAccess oauthAccess = oauthFlowService.protect(command.oauthAccess());
        oauthFlowStore.bindPhoneFlow(
                oauthAccess,
                protector.loginFlowToken(phoneFlow.rawFlowToken()),
                protector.loginChallenge(phoneFlow.challengeHandle()),
                phoneFlow.normalizedPhone(),
                clock.instant());
        return new OAuthPhoneStartResult(
                phoneFlow.rawFlowToken(),
                phoneFlow.challengeHandle(),
                phoneFlow.expiresAt());
    }

    @Override
    public Mono<Void> verifyTurnstile(OAuthPhoneAccess access, String turnstileToken) {
        Objects.requireNonNull(access, "access must not be null");
        oauthFlowService.getRequired(access.oauthAccess());
        LoginCodeAccess loginAccess = loginAccess(access);
        ProtectedOAuthFlowAccess outer = oauthFlowService.protect(access.oauthAccess());
        HmacIdentifier phoneFlowId = protector.loginFlowToken(access.rawPhoneFlowToken());
        HmacIdentifier challengeId = protector.loginChallenge(access.challengeHandle());
        return loginCodeFlowService.verifyTurnstile(loginAccess, turnstileToken)
                .then(Mono.fromRunnable(() -> oauthFlowStore.markPhoneHumanVerified(
                        outer, phoneFlowId, challengeId, clock.instant())));
    }

    @Override
    public void send(
            OAuthPhoneAccess access,
            VerificationDeliveryMethod deliveryMethod) {
        Objects.requireNonNull(access, "access must not be null");
        oauthFlowService.getRequired(access.oauthAccess());
        ProtectedOAuthFlowAccess outer = oauthFlowService.protect(access.oauthAccess());
        HmacIdentifier phoneFlowId = protector.loginFlowToken(access.rawPhoneFlowToken());
        HmacIdentifier challengeId = protector.loginChallenge(access.challengeHandle());
        oauthFlowStore.requirePhoneCodeReady(
                outer, phoneFlowId, challengeId, clock.instant());
        riskService.requireSendAllowed(outer, clock.instant());
        loginCodeFlowService.sendCode(loginAccess(access), deliveryMethod);
    }

    @Override
    public String verify(OAuthPhoneAccess access, String verificationCode) {
        Objects.requireNonNull(access, "access must not be null");
        oauthFlowService.getRequired(access.oauthAccess());
        ProtectedOAuthFlowAccess outer = oauthFlowService.protect(access.oauthAccess());
        HmacIdentifier phoneFlowId = protector.loginFlowToken(access.rawPhoneFlowToken());
        HmacIdentifier challengeId = protector.loginChallenge(access.challengeHandle());
        oauthFlowStore.requirePhoneCodeReady(
                outer, phoneFlowId, challengeId, clock.instant());
        String verifiedPhone = loginCodeFlowService.verifyOAuthPhone(
                new LoginStrategyRequest(
                        null,
                        null,
                        null,
                        null,
                        access.rawPhoneFlowToken(),
                        access.challengeHandle(),
                        verificationCode,
                        access.oauthAccess().deviceInstallationId(),
                        access.oauthAccess().canonicalIp()));
        oauthFlowStore.markPhoneVerified(
                outer,
                phoneFlowId,
                challengeId,
                verifiedPhone,
                clock.instant());
        return verifiedPhone;
    }

    private static LoginCodeAccess loginAccess(OAuthPhoneAccess access) {
        return new LoginCodeAccess(
                access.rawPhoneFlowToken(),
                access.challengeHandle(),
                access.oauthAccess().deviceInstallationId(),
                access.oauthAccess().canonicalIp());
    }
}
