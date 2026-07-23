package com.example.temperate.service.auth.login.code.dto;

import com.example.temperate.service.auth.login.strategy.LoginStrategyType;

/**
 * 承载启动邮箱或短信验证码登录流程的输入参数。
 */
public record LoginCodeStartCommand(
        LoginStrategyType strategyType,
        String email,
        String countryIso2,
        String phoneNumber,
        String deviceInstallationId,
        String clientIp) {
}
