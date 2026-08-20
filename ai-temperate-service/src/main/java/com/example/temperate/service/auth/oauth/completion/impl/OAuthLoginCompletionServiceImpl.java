package com.example.temperate.service.auth.oauth.completion.impl;

import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.login.completion.LoginCompletionService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.oauth.completion.OAuthLoginCompletionService;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowErrorCode;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowException;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowSnapshot;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowState;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.flow.ProtectedOAuthFlowAccess;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowService;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountFinalizationService;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountErrorCode;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountException;
import com.example.temperate.service.auth.oauth.phone.OAuthPhoneRiskService;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在一次性 OAuth 完成声明保护下重新执行数据库裁决，并复用统一登录完成服务签发会话或 TOTP 挑战。
 *
 * <p>Redis 声明只防止同一 Flow 重复签发，不替代 PostgreSQL 唯一索引。数据库事务或会话签发失败时撤销
 * 声明以允许受控重试；成功后只记录终态，不把会话令牌写入 OAuth Flow。</p>
 */
@Service
public final class OAuthLoginCompletionServiceImpl
        implements OAuthLoginCompletionService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OAuthLoginCompletionServiceImpl.class);

    private final OAuthFlowService flowService;
    private final OAuthFlowStore flowStore;
    private final OAuthAccountFinalizationService finalizationService;
    private final LoginCompletionService loginCompletionService;
    private final OAuthPhoneRiskService phoneRiskService;
    private final Clock clock;

    public OAuthLoginCompletionServiceImpl(
            OAuthFlowService flowService,
            OAuthFlowStore flowStore,
            OAuthAccountFinalizationService finalizationService,
            LoginCompletionService loginCompletionService,
            OAuthPhoneRiskService phoneRiskService,
            Clock clock) {
        this.flowService = Objects.requireNonNull(flowService);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.finalizationService = Objects.requireNonNull(finalizationService);
        this.loginCompletionService = Objects.requireNonNull(loginCompletionService);
        this.phoneRiskService = Objects.requireNonNull(phoneRiskService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public LoginResult complete(OAuthFlowAccess access) {
        Objects.requireNonNull(access, "access must not be null");
        ProtectedOAuthFlowAccess protectedAccess = flowService.protect(access);
        OAuthFlowSnapshot snapshot = flowService.getRequired(access);
        requireReady(snapshot);
        flowStore.claimCompletion(protectedAccess, clock.instant());
        try {
            AuthenticationContext context = finalizationService.finalizeIdentity(
                    snapshot.trustedIdentity(),
                    snapshot.phoneVerified() ? snapshot.lockedPhone() : null);
            LoginResult result = loginCompletionService.complete(
                    context, access.deviceInstallationId());
            try {
                flowStore.markCompletionResult(
                        protectedAccess,
                        result.isAuthenticated()
                                ? OAuthFlowState.AUTHENTICATED
                                : OAuthFlowState.TOTP_REQUIRED,
                        clock.instant());
            } catch (RuntimeException stateFailure) {
                // 会话材料已经生成后不能撤销一次性声明，否则客户端重试会再次签发；终态仅用于状态展示。
                LOGGER.warn("OAuth completion result state could not be recorded.", stateFailure);
            }
            return result;
        } catch (OAuthAccountException exception) {
            if (exception.code() == OAuthAccountErrorCode.PHONE_UNAVAILABLE) {
                // 冲突响应始终保持模糊，同时按 Flow 与全局设备记录探测次数并在第六次封禁两小时。
                phoneRiskService.recordPhoneConflict(protectedAccess, clock.instant());
            }
            releaseAfterFailure(protectedAccess, exception);
            throw exception;
        } catch (RuntimeException exception) {
            releaseAfterFailure(protectedAccess, exception);
            throw exception;
        }
    }

    private void releaseAfterFailure(
            ProtectedOAuthFlowAccess access,
            RuntimeException original) {
        try {
            flowStore.releaseCompletionClaim(access);
        } catch (RuntimeException releaseFailure) {
            original.addSuppressed(releaseFailure);
        }
    }

    private static void requireReady(OAuthFlowSnapshot snapshot) {
        if (snapshot.state() != OAuthFlowState.READY_TO_COMPLETE
                || snapshot.trustedIdentity() == null
                || (snapshot.phoneRequired() && !snapshot.phoneVerified())) {
            throw new OAuthFlowException(
                    OAuthFlowErrorCode.INVALID_TRANSITION,
                    "OAuth flow is not ready to complete.");
        }
    }
}
