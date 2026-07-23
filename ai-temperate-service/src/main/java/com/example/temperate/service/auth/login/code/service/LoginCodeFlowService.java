package com.example.temperate.service.auth.login.code.service;

import com.example.temperate.service.auth.login.code.dto.LoginCodeAccess;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartCommand;
import com.example.temperate.service.auth.login.code.dto.LoginCodeStartResult;
import com.example.temperate.service.auth.login.dto.result.LoginResult;
import com.example.temperate.service.auth.login.strategy.LoginStrategyRequest;
import com.example.temperate.service.auth.login.strategy.LoginStrategyType;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;

/**
 * 定义验证码登录的流程启动、人机校验、发码和最终登录编排能力。
 *
 * <p>接口负责业务边界，具体实现必须保护短生命周期流程凭据并协调风控、投递和会话签发。</p>
 */
public interface LoginCodeFlowService {

    LoginCodeStartResult start(LoginCodeStartCommand command);

    void verifyTurnstile(LoginCodeAccess access, String turnstileToken);

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
}
