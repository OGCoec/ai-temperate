package com.example.temperate.service.auth.login.limit.service;

import com.example.temperate.service.auth.login.limit.dto.LoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;

/**
 * 定义登录失败检查、记录和成功后清理的风控服务边界。
 *
 * <p>风控按密码和验证码桶隔离失败记录，调用方必须在认证决策的恰当阶段调用，不能把该接口当作身份认证本身。</p>
 */
public interface LoginRateLimitService {

    default LoginLimitDecision check(LoginAttempt attempt) {
        return check(attempt, LoginFailureBucket.PASSWORD);
    }

    LoginLimitDecision check(LoginAttempt attempt, LoginFailureBucket bucket);

    default LoginLimitDecision recordFailure(LoginAttempt attempt) {
        return recordFailure(attempt, LoginFailureBucket.PASSWORD);
    }

    LoginLimitDecision recordFailure(LoginAttempt attempt, LoginFailureBucket bucket);

    void clearSubjectFailures(LoginAttempt attempt);
}
