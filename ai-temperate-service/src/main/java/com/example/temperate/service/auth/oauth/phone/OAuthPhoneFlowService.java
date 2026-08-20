package com.example.temperate.service.auth.oauth.phone;

import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import reactor.core.publisher.Mono;

/**
 * 定义 OAuth 补手机号对子流程的启动、Turnstile、发码与验证码消费能力。
 */
public interface OAuthPhoneFlowService {

    OAuthPhoneStartResult start(OAuthPhoneStartCommand command);

    Mono<Void> verifyTurnstile(OAuthPhoneAccess access, String turnstileToken);

    void send(OAuthPhoneAccess access, VerificationDeliveryMethod deliveryMethod);

    String verify(OAuthPhoneAccess access, String verificationCode);
}
