package com.example.temperate.service.auth.oauth.identity.impl;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.model.auth.enums.AccountStatus;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.service.auth.identity.bloom.IdentityPresenceFilter;
import com.example.temperate.service.auth.oauth.domain.TrustedOAuthIdentity;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountErrorCode;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountException;
import com.example.temperate.service.auth.oauth.identity.OAuthAccountFinalizationService;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingRegistry;
import com.example.temperate.service.auth.oauth.identity.OAuthSubjectBindingStrategy;
import com.example.temperate.service.registration.component.executor.RegistrationAfterCommitExecutor;
import com.example.temperate.service.registration.component.id.RegistrationIdGenerator;
import com.example.temperate.service.registration.component.normalizer.RegistrationInputNormalizer;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在单个 PostgreSQL 本地事务中完成 OAuth Subject 绑定、手机号补全或新账号开户。
 *
 * <p>事务开始后必须重新按 Subject、邮箱和手机号查询，不能信任回调阶段的只读决定；Subject 条件更新与唯一索引
 * 共同裁决并发绑定。提交后才更新身份 Bloom 和删除资料缓存，回滚不会留下半绑定或半开户状态。</p>
 */
@Service
public final class OAuthAccountFinalizationServiceImpl
        implements OAuthAccountFinalizationService {

    private static final String E164_PATTERN = "^\\+[1-9][0-9]{7,14}$";

    private final UserLoginIdentityMapper identityMapper;
    private final UserProfileMapper profileMapper;
    private final UserMembershipQuotaMapper membershipQuotaMapper;
    private final MembershipQuotaPlanService quotaPlanService;
    private final RegistrationIdGenerator idGenerator;
    private final PublicIdCodec publicIdCodec;
    private final RegistrationInputNormalizer inputNormalizer;
    private final OAuthSubjectBindingRegistry strategyRegistry;
    private final RegistrationAfterCommitExecutor afterCommitExecutor;
    private final IdentityPresenceFilter identityPresenceFilter;
    private final UserProfileCacheInvalidationExecutor cacheInvalidationExecutor;
    private final Clock clock;

    public OAuthAccountFinalizationServiceImpl(
            UserLoginIdentityMapper identityMapper,
            UserProfileMapper profileMapper,
            UserMembershipQuotaMapper membershipQuotaMapper,
            MembershipQuotaPlanService quotaPlanService,
            RegistrationIdGenerator idGenerator,
            PublicIdCodec publicIdCodec,
            RegistrationInputNormalizer inputNormalizer,
            OAuthSubjectBindingRegistry strategyRegistry,
            RegistrationAfterCommitExecutor afterCommitExecutor,
            IdentityPresenceFilter identityPresenceFilter,
            UserProfileCacheInvalidationExecutor cacheInvalidationExecutor,
            Clock clock) {
        this.identityMapper = Objects.requireNonNull(identityMapper);
        this.profileMapper = Objects.requireNonNull(profileMapper);
        this.membershipQuotaMapper = Objects.requireNonNull(membershipQuotaMapper);
        this.quotaPlanService = Objects.requireNonNull(quotaPlanService);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.inputNormalizer = Objects.requireNonNull(inputNormalizer);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
        this.afterCommitExecutor = Objects.requireNonNull(afterCommitExecutor);
        this.identityPresenceFilter = Objects.requireNonNull(identityPresenceFilter);
        this.cacheInvalidationExecutor = Objects.requireNonNull(cacheInvalidationExecutor);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public AuthenticationContext finalizeIdentity(
            TrustedOAuthIdentity identity,
            String verifiedPhone) {
        Objects.requireNonNull(identity, "identity must not be null");
        OAuthSubjectBindingStrategy strategy = strategyRegistry.getRequired(identity.provider());
        String normalizedEmail = inputNormalizer.normalizeEmail(identity.verifiedEmail());
        String normalizedPhone = normalizeVerifiedPhone(verifiedPhone);
        try {
            // Subject 一旦存在就锁定其原账号并忽略 Provider 当前邮箱，防止第三方改邮箱导致账号漂移。
            UserLoginIdentity subjectMatch = strategy.findBySubject(identity.providerSubject());
            UserLoginIdentity target = subjectMatch == null
                    ? identityMapper.findByNormalizedEmailForUpdate(normalizedEmail)
                    : identityMapper.findByIdForUpdate(subjectMatch.getId());
            if (target == null) {
                return createIdentity(identity, strategy, normalizedEmail,
                        requirePhone(normalizedPhone));
            }
            return bindExisting(
                    identity, strategy, target, normalizedEmail, normalizedPhone);
        } catch (DataIntegrityViolationException exception) {
            // 唯一索引是不同请求并发占用 Subject、邮箱或手机号时的最终裁决，任何冲突都回滚整个本地事务。
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.ACCOUNT_CONFLICT,
                    "OAuth identity conflicts with an existing account.",
                    exception);
        }
    }

    private AuthenticationContext bindExisting(
            TrustedOAuthIdentity identity,
            OAuthSubjectBindingStrategy strategy,
            UserLoginIdentity target,
            String normalizedProviderEmail,
            String normalizedPhone) {
        String currentSubject = strategy.subjectOf(target);
        if (currentSubject == null) {
            if (strategy.bindIfAbsent(target.getId(), identity.providerSubject()) != 1) {
                requireIdempotentSubjectBinding(
                        strategy, target.getId(), identity.providerSubject());
            }
        } else if (!currentSubject.equals(identity.providerSubject())) {
            throw conflict();
        }
        // Subject 命中后 Provider 可能已更换邮箱；只有当前已验证邮箱仍等于本地邮箱时才能提升本地验证状态。
        if (target.getEmail() != null
                && target.getEmail().equalsIgnoreCase(normalizedProviderEmail)) {
            identityMapper.markEmailVerified(target.getId());
        }
        if (target.getPhone() == null || target.getPhone().isBlank()) {
            String phone = requirePhone(normalizedPhone);
            requirePhoneAvailable(phone, target.getId());
            fillVerifiedPhone(target.getId(), phone);
        }
        cacheInvalidationExecutor.evictAfterCommit(target.getId());
        return requireActiveContext(target.getId());
    }

    private void requireIdempotentSubjectBinding(
            OAuthSubjectBindingStrategy strategy,
            long identityId,
            String expectedSubject) {
        // 条件更新未命中时不能直接覆盖现值；重新锁定读取，只允许已经绑定同一 Subject 的幂等结果继续。
        UserLoginIdentity refreshed = identityMapper.findByIdForUpdate(identityId);
        if (refreshed == null) {
            throw persistenceFailure();
        }
        String persistedSubject = strategy.subjectOf(refreshed);
        if (!expectedSubject.equals(persistedSubject)) {
            throw conflict();
        }
    }

    private AuthenticationContext createIdentity(
            TrustedOAuthIdentity identity,
            OAuthSubjectBindingStrategy strategy,
            String normalizedEmail,
            String normalizedPhone) {
        requirePhoneAvailable(normalizedPhone, 0L);
        long identityId = idGenerator.nextPositiveId();
        if (identityId <= 0) {
            throw persistenceFailure();
        }
        String publicId = publicIdCodec.encode(identityId);
        UserLoginIdentity newIdentity = new UserLoginIdentity();
        newIdentity.setId(identityId);
        newIdentity.setRegistrationSource(strategy.registrationSource());
        strategy.applySubject(newIdentity, identity.providerSubject());
        newIdentity.setEmail(normalizedEmail);
        newIdentity.setEmailVerified(Boolean.TRUE);
        // 手机号使用独立条件更新写入，仍处于同一事务，但能把唯一索引竞态稳定归类为模糊手机号冲突。
        newIdentity.setPhone(null);
        newIdentity.setPasswordHash(null);
        // OAuth 专用插入不抛出唯一冲突，使相同 Subject 的并发首次登录可以在当前事务内重新裁决为幂等。
        if (identityMapper.insertOAuthIdentityIfAbsent(newIdentity) != 1) {
            return reconcileConcurrentCreation(
                    identity, strategy, normalizedEmail, normalizedPhone);
        }
        // 只有本事务真实创建身份后才登记提交回调，避免并发输家重复派生 Bloom 记录。
        afterCommitExecutor.execute(
                () -> identityPresenceFilter.recordRegistration(
                        identityId, normalizedEmail, normalizedPhone),
                () -> { });
        fillVerifiedPhone(identityId, normalizedPhone);

        UserProfile profile = new UserProfile();
        profile.setLoginIdentityId(identityId);
        profile.setDisplayName("用户" + publicId.substring(publicId.length() - 7));
        profile.setAccountStatus(0);
        if (profileMapper.insert(profile) != 1) {
            throw persistenceFailure();
        }

        MembershipQuotaPlan freePlan = quotaPlanService.getRequired(MembershipTier.FREE);
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(identityId);
        quota.setMembershipTier(MembershipTier.FREE.ordinal());
        quota.setQuotaBalanceMinor(freePlan.totalMinor());
        quota.setQuotaPeriodStartedAt(null);
        quota.setQuotaPeriodEndsAt(clock.instant().atOffset(ZoneOffset.UTC));
        if (membershipQuotaMapper.insert(quota) != 1) {
            throw persistenceFailure();
        }
        return requireActiveContext(identityId);
    }

    private AuthenticationContext reconcileConcurrentCreation(
            TrustedOAuthIdentity identity,
            OAuthSubjectBindingStrategy strategy,
            String normalizedEmail,
            String normalizedPhone) {
        // ON CONFLICT 等待并发事务完成后，READ COMMITTED 下重新读取已提交胜者，再按正常绑定规则复用账号。
        UserLoginIdentity subjectMatch = strategy.findBySubject(identity.providerSubject());
        if (subjectMatch != null) {
            UserLoginIdentity locked = identityMapper.findByIdForUpdate(subjectMatch.getId());
            if (locked == null) {
                throw persistenceFailure();
            }
            return bindExisting(identity, strategy, locked, normalizedEmail, normalizedPhone);
        }
        UserLoginIdentity emailMatch =
                identityMapper.findByNormalizedEmailForUpdate(normalizedEmail);
        if (emailMatch != null) {
            return bindExisting(identity, strategy, emailMatch, normalizedEmail, normalizedPhone);
        }
        // Subject 与邮箱都未命中通常只可能是极低概率的内部 ID 冲突，不能把它误报为账号合并成功。
        throw persistenceFailure();
    }

    private void requirePhoneAvailable(String phone, long allowedIdentityId) {
        UserLoginIdentity phoneMatch = identityMapper.findByNormalizedPhone(phone);
        if (phoneMatch != null
                && (phoneMatch.getId() == null || phoneMatch.getId() != allowedIdentityId)) {
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.PHONE_UNAVAILABLE,
                    "OAuth phone is unavailable.");
        }
    }

    private void fillVerifiedPhone(long identityId, String phone) {
        try {
            if (identityMapper.fillPhoneIfAbsent(identityId, phone) != 1) {
                throw conflict();
            }
        } catch (DataIntegrityViolationException exception) {
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.PHONE_UNAVAILABLE,
                    "OAuth phone is unavailable.",
                    exception);
        }
    }

    private AuthenticationContext requireActiveContext(long identityId) {
        AuthenticationContext context = identityMapper.findAuthenticationById(identityId);
        if (context == null || context.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.ACCOUNT_UNAVAILABLE,
                    "OAuth account is unavailable.");
        }
        return context;
    }

    private static String normalizeVerifiedPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String normalized = phone.trim();
        if (!normalized.matches(E164_PATTERN)) {
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.INVALID_IDENTITY,
                    "Verified OAuth phone is invalid.");
        }
        return normalized;
    }

    private static String requirePhone(String phone) {
        if (phone == null) {
            throw new OAuthAccountException(
                    OAuthAccountErrorCode.PHONE_REQUIRED,
                    "A verified phone is required.");
        }
        return phone;
    }

    private static OAuthAccountException conflict() {
        return new OAuthAccountException(
                OAuthAccountErrorCode.ACCOUNT_CONFLICT,
                "OAuth identity conflicts with an existing account.");
    }

    private static OAuthAccountException persistenceFailure() {
        return new OAuthAccountException(
                OAuthAccountErrorCode.PERSISTENCE_FAILED,
                "OAuth account persistence failed.");
    }
}
