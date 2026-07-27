package com.example.temperate.service.auth.passwordreset.service;

import com.example.temperate.service.auth.passwordreset.dto.ForgetTokenResult;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetAccess;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartCommand;
import com.example.temperate.service.auth.passwordreset.dto.PasswordResetStartResult;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import reactor.core.publisher.Mono;

/**
 * 定义密码重置流程的启动、人机验证、验证码验证和提交新密码业务边界。
 */
public interface PasswordResetService {

    PasswordResetStartResult start(PasswordResetStartCommand command);

    Mono<Void> verifyTurnstile(PasswordResetAccess access, String turnstileToken);

    void sendCode(PasswordResetAccess access);

    /**
     * 为手机号找回密码选择 SMS 或 WhatsApp；缺省值由实现按流程渠道解析，以兼容原有客户端。
     *
     * <p>默认实现仅保留旧实现的 SMS 行为，正式实现必须在生成验证码和修改 Redis 状态前完成服务端校验。</p>
     */
    default void sendCode(
            PasswordResetAccess access, VerificationDeliveryMethod deliveryMethod) {
        if (deliveryMethod != null && deliveryMethod != VerificationDeliveryMethod.SMS) {
            throw new UnsupportedOperationException("Password reset delivery method is unsupported.");
        }
        sendCode(access);
    }

    ForgetTokenResult verifyCode(PasswordResetAccess access, String code);

    void complete(
            String forgetToken,
            String deviceInstallationId,
            String password,
            String passwordConfirmation);
}
