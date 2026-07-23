package com.example.temperate.service.registration.dto.command;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.enums.VerificationDeliveryMethod;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 承载注册流程请求发送邮箱或手机验证码的访问参数，并将具体投递方式限制为服务端枚举。
 */
public record RegistrationSendCodeCommand(
        RegistrationAccess access,
        VerificationChannel channel,
        VerificationDeliveryMethod deliveryMethod) {

    public RegistrationSendCodeCommand(
            RegistrationAccess access, VerificationChannel channel) {
        this(access, channel, VerificationDeliveryMethod.defaultFor(channel));
    }
}
