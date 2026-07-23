package com.example.temperate.service.registration.verification.service;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;

/**
 * 统一执行注册六位数验证码的 Redis 原子校验。
 *
 * <p>该边界只处理项目自己的摘要比较、失败次数和成功消费，不发送验证码，也不调用 Gmail、阿里云或
 * Twilio 的校验接口。</p>
 */
public interface SixDigitVerificationCodeVerifier {

    /**
     * 校验并原子消费单一渠道验证码。
     *
     * @param command 注册访问凭据、渠道和六位数验证码
     * @return 校验完成后的注册流程状态
     */
    RegistrationStatusResult verify(RegistrationVerifyCodeCommand command);
}
