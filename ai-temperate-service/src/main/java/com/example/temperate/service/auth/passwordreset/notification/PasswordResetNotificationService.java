package com.example.temperate.service.auth.passwordreset.notification;

/**
 * 定义密码重置流程向邮箱主体发送安全通知的能力。
 *
 * <p>通知用于提示账号状态，不得充当验证码验证、密码更新或事务成功的唯一依据。</p>
 */
public interface PasswordResetNotificationService {

    void notifyEmailNotRegistered(String normalizedEmail);

    void notifyPasswordChanged(String normalizedEmail);
}
