package com.example.temperate.service.admin.registration;

import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 承载管理员首次初始化最后一步的注册 Flow 访问材料和两次密码输入。
 */
public record AdminRegistrationCompleteCommand(
        RegistrationAccess access,
        String password,
        String passwordConfirmation) {

    @Override
    public String toString() {
        return "AdminRegistrationCompleteCommand[redacted]";
    }
}
