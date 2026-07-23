package com.example.temperate.service.auth.login.strategy;

/**
 * 承载各类登录策略共享的原始请求字段。
 *
 * <p>字段集合覆盖密码和验证码登录，但每种策略只能读取自己支持的字段；密码和流程凭据不得跨出认证边界。</p>
 */
public record LoginStrategyRequest(
        String email,
        String countryIso2,
        String phoneNumber,
        String password,
        String loginFlowToken,
        String challengeHandle,
        String verificationCode,
        String deviceInstallationId,
        String clientIp) {
}
