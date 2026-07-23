package com.example.temperate.service.registration.dto.command;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 承载为注册流程提交的人机验证结果与流程访问材料。
 */
public record RegistrationTurnstileCommand(
        RegistrationAccess access,
        String responseToken) {
}
