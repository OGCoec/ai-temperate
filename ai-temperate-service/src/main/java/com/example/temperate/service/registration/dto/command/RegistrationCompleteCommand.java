package com.example.temperate.service.registration.dto.command;
import com.example.temperate.service.registration.flow.security.RegistrationAccess;

/**
 * 承载完成注册、设置密码并创建身份资料所需的流程与密码输入。
 *
 * <p>该命令含明文密码和一次性流程材料，只能在受控注册事务内使用，禁止记录或外部序列化。</p>
 */
public record RegistrationCompleteCommand(
        RegistrationAccess access,
        String password,
        String passwordConfirmation) {
}
