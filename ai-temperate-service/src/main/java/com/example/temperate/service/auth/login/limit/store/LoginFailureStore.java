package com.example.temperate.service.auth.login.limit.store;

import com.example.temperate.service.auth.login.limit.dto.ProtectedLoginAttempt;
import com.example.temperate.service.auth.login.limit.enums.LoginLimitDecision;
import com.example.temperate.service.auth.login.limit.enums.LoginFailureBucket;

/**
 * 定义受保护登录失败计数及封禁状态的存储契约。
 *
 * <p>实现必须保证失败递增、阈值封禁和清理操作在并发登录请求下保持一致。</p>
 */
public interface LoginFailureStore {

    LoginLimitDecision check(ProtectedLoginAttempt attempt, LoginFailureBucket bucket);

    LoginLimitDecision recordFailure(ProtectedLoginAttempt attempt, LoginFailureBucket bucket);

    void clearFailures(ProtectedLoginAttempt attempt);
}
