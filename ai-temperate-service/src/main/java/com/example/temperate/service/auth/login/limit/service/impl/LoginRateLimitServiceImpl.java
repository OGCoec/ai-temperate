package com.example.temperate.service.auth.login.limit.service.impl;

import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;
import com.example.temperate.service.auth.login.limit.exception.LoginRateLimitInfrastructureException;
import com.example.temperate.service.auth.login.limit.service.LoginRateLimitService;
import com.example.temperate.service.auth.login.limit.store.LoginFailureStore;
import com.example.temperate.service.auth.protection.component.AuthSessionSecretProtector;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 将原始登录尝试转换为受保护风控标识并委派给失败记录存储。
 *
 * <p>服务层不允许原始身份、设备或 IP 进入 Redis Key；存储不可用时统一转为受控基础设施异常，
 * 由认证流程按安全策略处理。</p>
 */
@Service
public final class LoginRateLimitServiceImpl implements LoginRateLimitService {

    private final LoginFailureStore failureStore;
    private final AuthSessionSecretProtector secretProtector;

    public LoginRateLimitServiceImpl(
            LoginFailureStore failureStore,
            AuthSessionSecretProtector secretProtector) {
        this.failureStore = Objects.requireNonNull(failureStore, "failureStore must not be null");
        this.secretProtector = Objects.requireNonNull(
                secretProtector, "secretProtector must not be null");
    }

    @Override
    public LoginLimitDecision check(LoginAttempt attempt, LoginFailureBucket bucket) {
        ProtectedLoginAttempt protectedAttempt = protect(attempt);
        try {
            return failureStore.check(protectedAttempt, Objects.requireNonNull(bucket));
        } catch (LoginRateLimitInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public LoginLimitDecision recordFailure(LoginAttempt attempt, LoginFailureBucket bucket) {
        ProtectedLoginAttempt protectedAttempt = protect(attempt);
        try {
            return failureStore.recordFailure(protectedAttempt, Objects.requireNonNull(bucket));
        } catch (LoginRateLimitInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void clearSubjectFailures(LoginAttempt attempt) {
        ProtectedLoginAttempt protectedAttempt = protect(attempt);
        try {
            failureStore.clearFailures(protectedAttempt);
        } catch (LoginRateLimitInfrastructureException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private ProtectedLoginAttempt protect(LoginAttempt attempt) {
        // 先完成 HMAC 保护再访问存储，保证任何后续 Redis 访问都不会携带可枚举的原始风控标识。
        return secretProtector.protect(Objects.requireNonNull(attempt, "attempt must not be null"));
    }

    private static LoginRateLimitInfrastructureException unavailable(Throwable cause) {
        return new LoginRateLimitInfrastructureException(
                "Login rate limiting is unavailable.", cause);
    }
}
