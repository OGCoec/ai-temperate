package com.example.temperate.service.registration.verification.service.impl;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.util.gmail.GmailApiMailUtil;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 通过 Gmail API 发送六位数邮箱验证码，并把用户输入校验委托给共享 Redis 校验器。
 *
 * <p>该实现只执行一次 Gmail 投递，不维护请求级状态，也不在供应商侧校验用户输入；RabbitMQ 负责有限
 * 延时重试，Redis 校验器负责摘要比较和成功消费。</p>
 */
@Service
@ConditionalOnBean(GmailApiMailUtil.class)
public final class GmailSixDigitVerificationCodeServiceImpl
        implements SixDigitVerificationCodeService {

    private final GmailApiMailUtil gmailApiMailUtil;
    private final SixDigitVerificationCodeVerifier codeVerifier;

    public GmailSixDigitVerificationCodeServiceImpl(
            GmailApiMailUtil gmailApiMailUtil,
            SixDigitVerificationCodeVerifier codeVerifier) {
        this.gmailApiMailUtil =
                Objects.requireNonNull(gmailApiMailUtil, "gmailApiMailUtil must not be null");
        this.codeVerifier =
                Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
    }

    @Override
    public VerificationProvider type() {
        return VerificationProvider.GMAIL;
    }

    @Override
    public Mono<VerificationDeliveryResult> sendCode(VerificationDeliveryRequest request) {
        return gmailApiMailUtil.sendVerificationCode(request);
    }

    @Override
    public RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command) {
        return codeVerifier.verify(command);
    }
}
