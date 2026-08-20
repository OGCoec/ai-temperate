package com.example.temperate.service.auth.login.code.service;

import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartCommand;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.code.dto.OAuthPhoneCodeStartCommand;
import com.example.temperate.service.auth.login.code.dto.OAuthPhoneCodeStartResult;
import com.example.temperate.model.auth.domain.AuthenticationContext;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import reactor.core.publisher.Mono;

/**
 * 定义验证码登录的流程启动、人机校验、发码和最终登录编排能力。
 *
 * <p>接口负责业务边界，具体实现必须保护短生命周期流程凭据并协调风控、投递和会话签发。</p>
 */
public interface LoginCodeFlowService {

    LoginCodeStartResult start(LoginCodeStartCommand command);

    /**
     * 由 OAuth 编排服务创建服务端固定用途的手机验证码流程；客户端不能提交 purpose。
     */
    OAuthPhoneCodeStartResult startOAuthPhone(OAuthPhoneCodeStartCommand command);

    /**
     * 为当前已认证用户使用数据库中的邮箱或手机号创建验证码复验流程，禁止客户端替换投递目标。
     */
    LoginCodeStartResult startForVerifiedIdentity(
            long userId,
            LoginStrategyType type,
            String deviceInstallationId,
            String clientIp);

    Mono<Void> verifyTurnstile(LoginCodeAccess access, String turnstileToken);

    void sendCode(LoginCodeAccess access);

    /**
     * 在保持旧调用默认使用短信的同时，允许手机验证码流程显式选择 SMS 或 WhatsApp。
     *
     * <p>默认实现只为旧实现保留 SMS 兼容能力；正式实现必须覆盖该方法并在写入验证码状态前校验
     * 流程渠道、目标国家和投递方式。</p>
     */
    default void sendCode(
            LoginCodeAccess access, VerificationDeliveryMethod deliveryMethod) {
        if (deliveryMethod != null && deliveryMethod != VerificationDeliveryMethod.SMS) {
            throw new UnsupportedOperationException("Login code delivery method is unsupported.");
        }
        sendCode(access);
    }

    LoginResult verifyAndLogin(LoginStrategyType type, LoginStrategyRequest request);

    /**
     * 原子消费登录验证码并返回已验证账号，不创建会话；仅供需要复用第一因子的受控敏感操作流程调用。
     */
    AuthenticationContext verifyPrimaryFactor(
            LoginStrategyType type,
            LoginStrategyRequest request);

    /**
     * 原子消费 OAUTH_PHONE 流程验证码并返回已经规范化且证明归属的 E.164 手机号，不创建登录会话。
     */
    String verifyOAuthPhone(LoginStrategyRequest request);
}
