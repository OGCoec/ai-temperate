package com.example.temperate.service.auth.login.strategy;

import com.example.temperate.service.auth.login.dto.result.LoginResult;

/**
 * 定义按登录方式执行认证的统一策略契约。
 *
 * <p>实现必须声明稳定的策略类型并保持无状态；策略选择由注册表统一完成，调用方不能依赖 Bean 名称。</p>
 */
public interface LoginStrategy {

    LoginStrategyType type();

    LoginResult login(LoginStrategyRequest request);
}
