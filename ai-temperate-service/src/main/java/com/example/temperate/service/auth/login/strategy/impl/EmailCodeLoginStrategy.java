package com.example.temperate.service.auth.login.strategy.impl;

import com.example.temperate.service.auth.login.code.service.LoginCodeFlowService;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.strategy.LoginStrategy;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import org.springframework.stereotype.Component;

/**
 * 将邮箱验证码登录请求委派给统一验证码流程服务的策略实现。
 */
@Component("emailCodeLoginStrategy")
public final class EmailCodeLoginStrategy implements LoginStrategy {

    private final LoginCodeFlowService flowService;

    public EmailCodeLoginStrategy(LoginCodeFlowService flowService) {
        this.flowService = flowService;
    }

    @Override
    public LoginStrategyType type() {
        return LoginStrategyType.EMAIL_CODE;
    }

    @Override
    public LoginResult login(LoginStrategyRequest request) {
        return flowService.verifyAndLogin(type(), request);
    }
}
