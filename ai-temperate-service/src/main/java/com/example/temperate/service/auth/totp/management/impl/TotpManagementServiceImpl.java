package com.example.temperate.service.auth.totp.management.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.domain.TotpCredential;
import com.example.temperate.service.auth.login.enums.LoginErrorCode;
import com.example.temperate.service.auth.login.exception.LoginException;
import com.example.temperate.service.auth.session.authentication.service.SessionAuthenticationService;
import com.example.temperate.service.auth.session.token.service.AuthTokenService;
import com.example.temperate.service.auth.totp.algorithm.TotpCodeService;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import com.example.temperate.service.auth.totp.management.TotpManagementAction;
import com.example.temperate.service.auth.totp.management.TotpManagementService;
import com.example.temperate.service.auth.totp.management.dto.TotpSetupResult;
import com.example.temperate.service.auth.totp.management.dto.TotpStateChangeResult;
import com.example.temperate.service.auth.totp.management.dto.TotpStatusResult;
import com.example.temperate.service.auth.totp.management.store.TotpSetupSnapshot;
import com.example.temperate.service.auth.totp.management.store.TotpSetupStore;
import com.example.temperate.service.auth.totp.security.TotpSecretProtector;
import com.example.temperate.service.auth.totp.stepup.TotpStepUpService;
import com.example.temperate.service.auth.totp.verification.CurrentTotpVerificationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 编排当前用户 TOTP 开启、轮换与关闭状态机，并维护 PostgreSQL 与 Redis 的安全边界。
 *
 * <p>待确认的新密钥仅以密文暂存在 Redis；确认前原数据库状态保持不变。数据库事务提交后才删除临时状态并撤销
 * Refresh Session，从而避免回滚时把用户的旧认证能力一并清除。</p>
 */
@Service
public final class TotpManagementServiceImpl implements TotpManagementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            TotpManagementServiceImpl.class);

    private final UserLoginIdentityMapper identityMapper;
    private final TotpStepUpService stepUpService;
    private final TotpCodeService codeService;
    private final TotpSecretProtector secretProtector;
    private final TotpSetupStore setupStore;
    private final CurrentTotpVerificationService currentTotpVerificationService;
    private final AuthTokenService tokenService;
    private final SessionAuthenticationService sessionAuthenticationService;
    private final TotpProperties properties;
    private final Clock clock;

    public TotpManagementServiceImpl(
            UserLoginIdentityMapper identityMapper,
            TotpStepUpService stepUpService,
            TotpCodeService codeService,
            TotpSecretProtector secretProtector,
            TotpSetupStore setupStore,
            CurrentTotpVerificationService currentTotpVerificationService,
            AuthTokenService tokenService,
            SessionAuthenticationService sessionAuthenticationService,
            TotpProperties properties,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.stepUpService = Objects.requireNonNull(stepUpService);
        this.codeService = Objects.requireNonNull(codeService);
        this.secretProtector = Objects.requireNonNull(secretProtector);
        this.setupStore = Objects.requireNonNull(setupStore);
        this.currentTotpVerificationService = Objects.requireNonNull(
                currentTotpVerificationService);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.sessionAuthenticationService = Objects.requireNonNull(
                sessionAuthenticationService);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public TotpStatusResult status(long userId) {
        TotpCredential credential = requiredCredential(userId);
        ensureStoredStateConsistent(credential);
        return new TotpStatusResult(credential.enabled());
    }

    @Override
    public TotpSetupResult startSetup(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String stepUpToken,
            String currentTotpCode) {
        requireAction(action, TotpManagementAction.ENABLE, TotpManagementAction.ROTATE);
        stepUpService.requireProof(userId, deviceInstallationId, action, stepUpToken);

        AuthenticationContext context = requiredContext(userId);
        TotpCredential credential = requiredCredential(userId);
        ensureStoredStateConsistent(credential);
        if (action == TotpManagementAction.ENABLE && credential.enabled()) {
            throw stateConflict("TOTP is already enabled.");
        }
        if (action == TotpManagementAction.ROTATE && !credential.enabled()) {
            throw stateConflict("TOTP must be enabled before rotation.");
        }

        Instant now = clock.instant();
        if (action == TotpManagementAction.ROTATE) {
            // 轮换会废弃现用密钥，因此除第一因子复验外还必须证明调用者持有当前认证器。
            verifyCurrentTotp(
                    userId,
                    deviceInstallationId,
                    action,
                    stepUpToken,
                    currentTotpCode,
                    now);
        }
        // 当前状态和现用认证器都校验成功后才消费 step-up，输入错误时允许在五分钟窗口内继续尝试。
        stepUpService.consumeProof(userId, deviceInstallationId, action, stepUpToken);

        byte[] secret = codeService.newSecret();
        try {
            String secretBase32 = codeService.encodeBase32(secret);
            String provisioningUri = codeService.provisioningUri(
                    accountLabel(context), secret);
            String encryptedSecret = secretProtector.encrypt(userId, secret);
            String setupToken = tokenService.newFlowToken();
            setupStore.save(
                    userId,
                    setupToken,
                    deviceInstallationId,
                    encryptedSecret,
                    action,
                    credential.enabled(),
                    credential.encryptedSecret(),
                    now,
                    properties.setupTtl());
            return new TotpSetupResult(
                    setupToken,
                    secretBase32,
                    provisioningUri,
                    now.plus(properties.setupTtl()));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    @Transactional
    public TotpStateChangeResult confirmSetup(
            long userId,
            String deviceInstallationId,
            String setupToken,
            String code) {
        Instant now = clock.instant();
        TotpSetupSnapshot setup = setupStore.getRequired(
                userId, setupToken, deviceInstallationId, now);
        requireAction(
                setup.action(),
                TotpManagementAction.ENABLE,
                TotpManagementAction.ROTATE);
        byte[] secret = decryptPending(userId, setup.encryptedSecret());
        OptionalLong matched;
        try {
            matched = codeService.findMatchingTimeStep(secret, code, now);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        if (matched.isEmpty()) {
            setupStore.recordFailure(userId, setupToken, deviceInstallationId, now);
            throw new LoginException(
                    LoginErrorCode.TOTP_CODE_INVALID,
                    "TOTP code is invalid.");
        }

        TotpCredential current = requiredCredential(userId);
        ensureStoredStateConsistent(current);
        if (current.enabled() != setup.expectedEnabled()
                || !Objects.equals(
                        current.encryptedSecret(),
                        setup.expectedEncryptedSecret())) {
            throw stateConflict("TOTP state changed before setup confirmation.");
        }
        if (identityMapper.enableOrRotateTotp(
                userId,
                setup.encryptedSecret(),
                setup.expectedEnabled(),
                setup.expectedEncryptedSecret()) != 1) {
            throw stateConflict("TOTP state could not be updated.");
        }

        // 只有数据库成功提交后才销毁待确认状态和旧会话；事务回滚时仍允许用户重试确认。
        afterCommit(() -> {
            safeDeleteSetup(userId, setupToken);
            safeRevokeSessions(userId);
        });
        return new TotpStateChangeResult(true, true);
    }

    @Override
    @Transactional
    public TotpStateChangeResult disable(
            long userId,
            String deviceInstallationId,
            String stepUpToken,
            String currentTotpCode) {
        stepUpService.requireProof(
                userId,
                deviceInstallationId,
                TotpManagementAction.DISABLE,
                stepUpToken);
        TotpCredential credential = requiredCredential(userId);
        ensureStoredStateConsistent(credential);
        if (!credential.enabled()) {
            throw stateConflict("TOTP is already disabled.");
        }
        verifyCurrentTotp(
                userId,
                deviceInstallationId,
                TotpManagementAction.DISABLE,
                stepUpToken,
                currentTotpCode,
                clock.instant());
        stepUpService.consumeProof(
                userId,
                deviceInstallationId,
                TotpManagementAction.DISABLE,
                stepUpToken);
        if (identityMapper.disableTotp(userId) != 1) {
            throw stateConflict("TOTP state could not be disabled.");
        }

        // 关闭时数据库会在同一 SQL 中清空密文；提交后再清除临时设置并让全部设备重新登录。
        afterCommit(() -> {
            safeDeleteAllSetups(userId);
            safeRevokeSessions(userId);
        });
        return new TotpStateChangeResult(false, true);
    }

    private AuthenticationContext requiredContext(long userId) {
        AuthenticationContext context = identityMapper.findAuthenticationById(userId);
        if (context == null || context.getIdentityId() != userId) {
            throw stateConflict("User authentication context does not exist.");
        }
        return context;
    }

    private void verifyCurrentTotp(
            long userId,
            String deviceInstallationId,
            TotpManagementAction action,
            String stepUpToken,
            String currentTotpCode,
            Instant now) {
        try {
            currentTotpVerificationService.verifyAndClaim(
                    userId, currentTotpCode, now);
        } catch (LoginException exception) {
            if (exception.code() == LoginErrorCode.TOTP_CODE_INVALID
                    || exception.code() == LoginErrorCode.TOTP_CODE_REPLAYED) {
                // 当前认证器试码次数绑定到 step-up proof，达到上限后必须重新执行第一因子复验。
                stepUpService.recordProofFailure(
                        userId,
                        deviceInstallationId,
                        action,
                        stepUpToken);
            }
            throw exception;
        }
    }

    private TotpCredential requiredCredential(long userId) {
        if (userId <= 0) {
            throw invalid();
        }
        TotpCredential credential = identityMapper.findTotpCredentialById(userId);
        if (credential == null || credential.identityId() != userId) {
            throw stateConflict("User TOTP state does not exist.");
        }
        return credential;
    }

    private static void ensureStoredStateConsistent(TotpCredential credential) {
        boolean secretPresent = credential.encryptedSecret() != null
                && !credential.encryptedSecret().isBlank();
        if (credential.enabled() != secretPresent) {
            throw new LoginException(
                    LoginErrorCode.TOTP_CONFIGURATION_INVALID,
                    "Stored TOTP state is inconsistent.");
        }
    }

    private byte[] decryptPending(long userId, String encryptedSecret) {
        try {
            return secretProtector.decrypt(userId, encryptedSecret);
        } catch (RuntimeException exception) {
            throw new LoginException(
                    LoginErrorCode.TOTP_CONFIGURATION_INVALID,
                    "TOTP configuration is unavailable.",
                    exception);
        }
    }

    private static String accountLabel(AuthenticationContext context) {
        if (context.getEmail() != null && !context.getEmail().isBlank()) {
            return context.getEmail();
        }
        if (context.getPhone() != null && !context.getPhone().isBlank()) {
            return context.getPhone();
        }
        return "user-" + context.getIdentityId();
    }

    private static void requireAction(
            TotpManagementAction actual,
            TotpManagementAction first,
            TotpManagementAction second) {
        if (actual != first && actual != second) {
            throw invalid();
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
            return;
        }
        action.run();
    }

    private void safeDeleteSetup(long userId, String setupToken) {
        try {
            setupStore.delete(userId, setupToken);
        } catch (RuntimeException exception) {
            LOGGER.error("TOTP setup cleanup failed after database commit", exception);
        }
    }

    private void safeDeleteAllSetups(long userId) {
        try {
            setupStore.deleteForUser(userId);
        } catch (RuntimeException exception) {
            LOGGER.error("TOTP setup cleanup failed after disable commit", exception);
        }
    }

    private void safeRevokeSessions(long userId) {
        try {
            sessionAuthenticationService.logoutAllForUser(userId);
        } catch (RuntimeException exception) {
            LOGGER.error("TOTP session revocation failed after database commit", exception);
        }
    }

    private static LoginException invalid() {
        return new LoginException(
                LoginErrorCode.INVALID_INPUT,
                "TOTP management request is invalid.");
    }

    private static LoginException stateConflict(String message) {
        return new LoginException(LoginErrorCode.TOTP_STATE_CONFLICT, message);
    }
}
