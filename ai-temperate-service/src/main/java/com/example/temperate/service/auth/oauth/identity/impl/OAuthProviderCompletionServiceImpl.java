package com.example.temperate.service.auth.oauth.identity.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.auth.oauth.diagnostic.OAuthAccountResolutionDiagnosticContext;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.flow.OAuthFlowStore;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecision;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountResolutionService;
import com.example.temperate.service.auth.oauth.identity.OAuthProviderCompletionService;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 把可信 Provider 身份按 Subject 优先规则解析为账号决定，并原子推进对应 OAuth Flow。
 *
 * <p>该步骤只保存已验证邮箱、稳定 Subject 和决定摘要，不提前修改数据库；需要补手机号的流程只有在验证码
 * 完成后的独立事务中才会绑定或创建账号。</p>
 */
@Service
public final class OAuthProviderCompletionServiceImpl
        implements OAuthProviderCompletionService {

    private final OAuthAccountResolutionService resolutionService;
    private final OAuthFlowStore flowStore;
    private final Clock clock;

    public OAuthProviderCompletionServiceImpl(
            OAuthAccountResolutionService resolutionService,
            OAuthFlowStore flowStore,
            Clock clock) {
        this.resolutionService = Objects.requireNonNull(resolutionService);
        this.flowStore = Objects.requireNonNull(flowStore);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public OAuthAccountDecision accept(
            HmacIdentifier flowId,
            TrustedOAuthIdentity identity) {
        OAuthAccountDecision decision = resolutionService.resolve(identity);
        OAuthAccountResolutionDiagnosticContext.mark(
                OAuthAccountResolutionDiagnosticContext.Stage.FLOW_PERSISTENCE);
        flowStore.completeProvider(
                Objects.requireNonNull(flowId),
                Objects.requireNonNull(identity),
                decision,
                clock.instant());
        return decision;
    }
}
