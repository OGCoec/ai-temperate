package com.example.temperate.service.auth.oauth.identity.impl;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.service.auth.oauth.diagnostic.OAuthAccountResolutionDiagnosticContext;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecision;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountDecisionType;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountErrorCode;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountException;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountResolutionService;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingRegistry;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingStrategy;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 按 Subject 优先、已验证邮箱次之的固定顺序解析 OAuth 身份对应的本地账号。
 *
 * <p>该服务只读数据库，不提前绑定 Subject；手机号补验完成后的独立事务会重新读取并裁决，避免在中断流程中
 * 留下半绑定账号。Provider 后续更换邮箱时，只要 Subject 已绑定就始终返回原账号。</p>
 */
@Service
public final class OAuthAccountResolutionServiceImpl implements OAuthAccountResolutionService {

    private final UserLoginIdentityMapper identityMapper;
    private final RegistrationInputNormalizer inputNormalizer;
    private final OAuthSubjectBindingRegistry strategyRegistry;

    public OAuthAccountResolutionServiceImpl(
            UserLoginIdentityMapper identityMapper,
            RegistrationInputNormalizer inputNormalizer,
            OAuthSubjectBindingRegistry strategyRegistry) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
    }

    @Override
    public OAuthAccountDecision resolve(TrustedOAuthIdentity identity) {
        Objects.requireNonNull(identity, "identity must not be null");
        OAuthSubjectBindingStrategy strategy = strategyRegistry.getRequired(identity.provider());
        OAuthAccountResolutionDiagnosticContext.mark(
                OAuthAccountResolutionDiagnosticContext.Stage.SUBJECT_LOOKUP);
        UserLoginIdentity subjectMatch = strategy.findBySubject(identity.providerSubject());
        if (subjectMatch != null) {
            return decision(identity, requireActiveContext(subjectMatch.getId()));
        }

        String normalizedEmail = inputNormalizer.normalizeEmail(identity.verifiedEmail());
        OAuthAccountResolutionDiagnosticContext.mark(
                OAuthAccountResolutionDiagnosticContext.Stage.EMAIL_LOOKUP);
        UserLoginIdentity emailMatch = identityMapper.findByNormalizedEmail(normalizedEmail);
        if (emailMatch == null) {
            return new OAuthAccountDecision(
                    OAuthAccountDecisionType.PHONE_REQUIRED,
                    identity.provider(),
                    0L,
                    true);
        }
        String currentSubject = strategy.subjectOf(emailMatch);
        if (currentSubject != null && !currentSubject.equals(identity.providerSubject())) {
            throw conflict();
        }
        return decision(identity, requireActiveContext(emailMatch.getId()));
    }

    private OAuthAccountDecision decision(
            TrustedOAuthIdentity identity, AuthenticationContext context) {
        boolean phoneRequired = context.getPhone() == null || context.getPhone().isBlank();
        return new OAuthAccountDecision(
                phoneRequired
                        ? OAuthAccountDecisionType.PHONE_REQUIRED
                        : OAuthAccountDecisionType.AUTHENTICATE,
                identity.provider(),
                context.getIdentityId(),
                phoneRequired);
    }

    private AuthenticationContext requireActiveContext(Long identityId) {
        if (identityId == null || identityId <= 0) {
            throw unavailable();
        }
        OAuthAccountResolutionDiagnosticContext.mark(
                OAuthAccountResolutionDiagnosticContext.Stage.AUTH_CONTEXT_LOOKUP);
        AuthenticationContext context = identityMapper.findAuthenticationById(identityId);
        if (context == null || context.getAccountStatus() != AccountStatus.ACTIVE) {
            throw unavailable();
        }
        return context;
    }

    private static OAuthAccountException conflict() {
        return new OAuthAccountException(
                OAuthAccountErrorCode.ACCOUNT_CONFLICT,
                "OAuth identity conflicts with an existing account.");
    }

    private static OAuthAccountException unavailable() {
        return new OAuthAccountException(
                OAuthAccountErrorCode.ACCOUNT_UNAVAILABLE,
                "OAuth account is unavailable.");
    }
}
