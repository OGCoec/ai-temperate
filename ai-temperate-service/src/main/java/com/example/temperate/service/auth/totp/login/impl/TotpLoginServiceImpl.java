package com.example.temperate.service.auth.totp.login.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.domain.TotpCredential;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.login.session.LoginSessionIssuer;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.auth.totp.algorithm.TotpCodeService;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.login.TotpLoginService;
import com.example.temperate.service.auth.totp.login.dto.TotpLoginChallengeResult;
import com.example.temperate.service.auth.totp.login.store.TotpLoginChallengeSnapshot;
import com.example.temperate.service.auth.totp.login.store.TotpLoginChallengeStore;
import com.example.temperate.service.auth.totp.security.TotpSecretProtector;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/**
 * 编排 TOTP 登录挑战创建、当前数据库状态重读、验证码校验和最终会话签发。
 *
 * <p>挑战只保存用户 ID 和设备摘要，不保存密码哈希或 TOTP 密钥；最终校验必须重新读取当前账号和密钥状态，
 * 因此轮换、关闭或冻结账号会立即影响尚未完成的登录流程。</p>
 */
@Service
public final class TotpLoginServiceImpl implements TotpLoginService {

    private final TotpLoginChallengeStore challengeStore;
    private final UserLoginIdentityMapper identityMapper;
    private final TotpCodeService codeService;
    private final TotpSecretProtector secretProtector;
    private final LoginSessionIssuer sessionIssuer;
    private final AuthTokenService tokenService;
    private final AuthSessionSecretProtector identifierProtector;
    private final TotpProperties properties;
    private final Clock clock;

    public TotpLoginServiceImpl(
            TotpLoginChallengeStore challengeStore,
            UserLoginIdentityMapper identityMapper,
            TotpCodeService codeService,
            TotpSecretProtector secretProtector,
            LoginSessionIssuer sessionIssuer,
            AuthTokenService tokenService,
            AuthSessionSecretProtector identifierProtector,
            TotpProperties properties,
            Clock clock) {
        this.challengeStore = Objects.requireNonNull(challengeStore);
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.codeService = Objects.requireNonNull(codeService);
        this.secretProtector = Objects.requireNonNull(secretProtector);
        this.sessionIssuer = Objects.requireNonNull(sessionIssuer);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.identifierProtector = Objects.requireNonNull(identifierProtector);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public TotpLoginChallengeResult start(
            long userId,
            String deviceInstallationId) {
        if (userId <= 0) {
            throw invalid();
        }
        String rawFlowToken = tokenService.newFlowToken();
        HmacIdentifier flowId;
        HmacIdentifier deviceId;
        try {
            flowId = identifierProtector.totpLoginFlowToken(rawFlowToken);
            deviceId = identifierProtector.device(deviceInstallationId);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        Instant now = clock.instant();
        challengeStore.create(
                flowId,
                deviceId,
                userId,
                now,
                properties.loginChallengeTtl());
        return new TotpLoginChallengeResult(
                rawFlowToken,
                now.plus(properties.loginChallengeTtl()),
                properties.maxAttempts());
    }

    @Override
    public LoginResult verify(
            String rawFlowToken,
            String deviceInstallationId,
            String code) {
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw invalid();
        }
        HmacIdentifier flowId;
        HmacIdentifier deviceId;
        try {
            flowId = identifierProtector.totpLoginFlowToken(rawFlowToken);
            deviceId = identifierProtector.device(deviceInstallationId);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        Instant now = clock.instant();
        TotpLoginChallengeSnapshot challenge = challengeStore.getRequired(
                flowId, deviceId, now);
        AuthenticationContext context = identityMapper.findAuthenticationById(
                challenge.userId());
        TotpCredential credential = identityMapper.findTotpCredentialById(
                challenge.userId());
        requireActiveConfiguration(challenge, context, credential);

        final byte[] secret;
        try {
            secret = secretProtector.decrypt(
                    challenge.userId(), credential.encryptedSecret());
        } catch (RuntimeException exception) {
            throw new LoginException(
                    LoginErrorCode.TOTP_CONFIGURATION_INVALID,
                    "TOTP configuration is unavailable.",
                    exception);
        }
        OptionalLong matched;
        try {
            matched = codeService.findMatchingTimeStep(secret, code, now);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (matched.isEmpty()) {
            challengeStore.recordFailure(flowId, deviceId, now);
            throw new LoginException(
                    LoginErrorCode.TOTP_CODE_INVALID,
                    "TOTP code is invalid.");
        }
        HmacIdentifier replayId = identifierProtector.totpUsedTimeStep(
                challenge.userId(), matched.getAsLong());
        // 挑战和时间片必须在签发会话前一次性领取；领取后基础设施失败要求用户重新执行第一因子。
        challengeStore.consumeSuccessful(flowId, deviceId, replayId, now);
        return sessionIssuer.issue(context, deviceInstallationId);
    }

    private static void requireActiveConfiguration(
            TotpLoginChallengeSnapshot challenge,
            AuthenticationContext context,
            TotpCredential credential) {
        if (context == null
                || context.getIdentityId() != challenge.userId()
                || context.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new LoginException(
                    LoginErrorCode.ACCOUNT_UNAVAILABLE,
                    "Account is unavailable.");
        }
        if (!context.isTotpEnabled()
                || credential == null
                || credential.identityId() != challenge.userId()
                || !credential.enabled()) {
            throw new LoginException(
                    LoginErrorCode.TOTP_FLOW_FORBIDDEN,
                    "TOTP login flow is no longer valid.");
        }
        if (credential.encryptedSecret() == null
                || credential.encryptedSecret().isBlank()) {
            throw new LoginException(
                    LoginErrorCode.TOTP_CONFIGURATION_INVALID,
                    "TOTP configuration is unavailable.");
        }
    }

    private static LoginException invalid() {
        return new LoginException(
                LoginErrorCode.INVALID_INPUT,
                "TOTP login request is invalid.");
    }
}
