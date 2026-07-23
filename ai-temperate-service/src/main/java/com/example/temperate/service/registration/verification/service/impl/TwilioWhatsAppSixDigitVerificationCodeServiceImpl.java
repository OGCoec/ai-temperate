package com.example.temperate.service.registration.verification.service.impl;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioWhatsAppMessagingUtil;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 通过 Twilio Programmable Messaging 向国际 WhatsApp 号码发送六位验证码，并复用项目 Redis 校验器。
 *
 * <p>该实现只增加一种手机号验证码投递方式，不创建独立验证码状态；SMS 与 WhatsApp 因而共享限流、
 * 摘要、验证失败次数和成功后的原子删除。</p>
 */
@Service
@ConditionalOnBean(TwilioWhatsAppMessagingUtil.class)
public final class TwilioWhatsAppSixDigitVerificationCodeServiceImpl
        implements SixDigitVerificationCodeService {

    private final TwilioWhatsAppMessagingUtil messagingUtil;
    private final SixDigitVerificationCodeVerifier codeVerifier;

    public TwilioWhatsAppSixDigitVerificationCodeServiceImpl(
            TwilioWhatsAppMessagingUtil messagingUtil,
            SixDigitVerificationCodeVerifier codeVerifier) {
        this.messagingUtil = Objects.requireNonNull(messagingUtil, "messagingUtil must not be null");
        this.codeVerifier = Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
    }

    @Override
    public VerificationProvider type() {
        return VerificationProvider.TWILIO_WHATSAPP;
    }

    @Override
    public Mono<VerificationDeliveryResult> sendCode(VerificationDeliveryRequest request) {
        return messagingUtil.sendVerificationCode(request);
    }

    @Override
    public RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command) {
        return codeVerifier.verify(command);
    }
}
