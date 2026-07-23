package com.example.temperate.service.registration.verification.service.impl;

import com.example.temperate.service.registration.dto.command.RegistrationVerifyCodeCommand;
import com.example.temperate.service.registration.dto.result.RegistrationStatusResult;
import com.example.temperate.service.registration.enums.VerificationProvider;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioVerifySmsUtil;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeService;
import com.example.temperate.service.registration.verification.service.SixDigitVerificationCodeVerifier;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 通过 Twilio Verify 向非中国大陆国际号码发送六位数短信验证码，并共享项目 Redis 校验逻辑。
 *
 * <p>发送时使用项目生成的自定义验证码；校验时只委托 Redis 校验器，明确不调用 Twilio
 * VerificationCheck，避免第三方状态与项目验证码状态形成双重真相源。</p>
 */
@Service
@ConditionalOnBean(TwilioVerifySmsUtil.class)
public final class TwilioSmsSixDigitVerificationCodeServiceImpl
        implements SixDigitVerificationCodeService {

    private final TwilioVerifySmsUtil twilioVerifySmsUtil;
    private final SixDigitVerificationCodeVerifier codeVerifier;

    public TwilioSmsSixDigitVerificationCodeServiceImpl(
            TwilioVerifySmsUtil twilioVerifySmsUtil,
            SixDigitVerificationCodeVerifier codeVerifier) {
        this.twilioVerifySmsUtil =
                Objects.requireNonNull(twilioVerifySmsUtil, "twilioVerifySmsUtil must not be null");
        this.codeVerifier =
                Objects.requireNonNull(codeVerifier, "codeVerifier must not be null");
    }

    @Override
    public VerificationProvider type() {
        return VerificationProvider.TWILIO_SMS;
    }

    @Override
    public Mono<VerificationDeliveryResult> sendCode(VerificationDeliveryRequest request) {
        return twilioVerifySmsUtil.sendVerificationCode(request);
    }

    @Override
    public RegistrationStatusResult verifyCode(RegistrationVerifyCodeCommand command) {
        return codeVerifier.verify(command);
    }
}
