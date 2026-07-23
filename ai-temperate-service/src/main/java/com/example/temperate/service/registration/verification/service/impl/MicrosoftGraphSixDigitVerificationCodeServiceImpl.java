package com.example.temperate.service.registration.verification.service.impl;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiMailUtil;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 通过 Microsoft Graph API 发送六位数邮箱验证码，并把用户输入校验委托给共享 Redis 校验器。
 *
 * <p>该实现只执行一次 Graph 投递，不调用 Microsoft 的验证码校验能力；RabbitMQ 负责有限重试和每次
 * 尝试的邮件供应商重选，Redis 校验器负责摘要比较、失败计数和成功消费。</p>
 */
@Service
@ConditionalOnBean(MicrosoftGraphApiMailUtil.class)
public final class MicrosoftGraphSixDigitVerificationCodeServiceImpl
        implements SixDigitVerificationCodeService {

    private final MicrosoftGraphApiMailUtil microsoftGraphApiMailUtil;
    private final SixDigitVerificationCodeVerifier codeVerifier;

    public MicrosoftGraphSixDigitVerificationCodeServiceImpl(
            MicrosoftGraphApiMailUtil microsoftGraphApiMailUtil,
            SixDigitVerificationCodeVerifier codeVerifier) {
        this.microsoftGraphApiMailUtil = Objects.requireNonNull(
                microsoftGraphApiMailUtil, "microsoftGraphApiMailUtil must not be null");
        this.codeVerifier =
                Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
    }

    @Override
    public VerificationProvider type() {
        return VerificationProvider.MICROSOFT_GRAPH;
    }

    @Override
    public Mono<VerificationDeliveryResult> sendCode(
            VerificationDeliveryRequest request) {
        return microsoftGraphApiMailUtil.sendVerificationCode(request);
    }

    @Override
    public RegistrationStatusResult verifyCode(
            RegistrationVerifyCodeCommand command) {
        return codeVerifier.verify(command);
    }
}
