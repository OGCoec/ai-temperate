package com.example.temperate.service.auth.totp.verification.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.TotpCredential;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import com.example.temperate.service.auth.totp.algorithm.TotpCodeService;
import com.example.temperate.service.auth.totp.security.TotpSecretProtector;
import com.example.temperate.service.auth.totp.verification.CurrentTotpVerificationService;
import com.example.temperate.service.auth.totp.verification.TotpTimeStepReplayStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;

/**
 * 使用数据库当前生效密钥校验轮换或关闭请求中的 TOTP，并在 Redis 领取对应时间片。
 */
@Service
public final class CurrentTotpVerificationServiceImpl
        implements CurrentTotpVerificationService {

    private static final Duration REPLAY_TTL = Duration.ofSeconds(90);

    private final UserLoginIdentityMapper identityMapper;
    private final TotpSecretProtector secretProtector;
    private final TotpCodeService codeService;
    private final AuthSessionSecretProtector identifierProtector;
    private final TotpTimeStepReplayStore replayStore;

    public CurrentTotpVerificationServiceImpl(
            UserLoginIdentityMapper identityMapper,
            TotpSecretProtector secretProtector,
            TotpCodeService codeService,
            AuthSessionSecretProtector identifierProtector,
            TotpTimeStepReplayStore replayStore) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.secretProtector = Objects.requireNonNull(secretProtector);
        this.codeService = Objects.requireNonNull(codeService);
        this.identifierProtector = Objects.requireNonNull(identifierProtector);
        this.replayStore = Objects.requireNonNull(replayStore);
    }

    @Override
    public void verifyAndClaim(long userId, String code, Instant now) {
        if (userId <= 0 || code == null || !code.matches("^[0-9]{6}$") || now == null) {
            throw invalid();
        }
        TotpCredential credential = identityMapper.findTotpCredentialById(userId);
        if (credential == null
                || credential.identityId() != userId
                || !credential.enabled()
                || credential.encryptedSecret() == null
                || credential.encryptedSecret().isBlank()) {
            throw new LoginException(
                    LoginErrorCode.TOTP_STATE_CONFLICT,
                    "TOTP is not enabled for the current user.");
        }
        final byte[] secret;
        try {
            secret = secretProtector.decrypt(userId, credential.encryptedSecret());
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
            throw new LoginException(
                    LoginErrorCode.TOTP_CODE_INVALID,
                    "TOTP code is invalid.");
        }
        HmacIdentifier replayId = identifierProtector.totpUsedTimeStep(
                userId, matched.getAsLong());
        if (!replayStore.claim(replayId, REPLAY_TTL)) {
            throw new LoginException(
                    LoginErrorCode.TOTP_CODE_REPLAYED,
                    "TOTP code was already used.");
        }
    }

    private static LoginException invalid() {
        return new LoginException(
                LoginErrorCode.INVALID_INPUT,
                "TOTP verification request is invalid.");
    }
}
