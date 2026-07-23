package com.example.temperate.service.registration.dto.command;
import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 承载注册流程校验单一渠道验证码的访问参数和验证码文本。
 */
public record RegistrationVerifyCodeCommand(
        RegistrationAccess access,
        VerificationChannel channel,
        String code) {
}
