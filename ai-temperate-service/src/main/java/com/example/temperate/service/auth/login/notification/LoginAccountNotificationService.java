package com.example.temperate.service.auth.login.notification;

/**
 * 定义登录流程向账号主体发送非认证性通知的能力。
 *
 * <p>通知不得改变登录结果，也不得用于证明账号存在或替代验证码投递。</p>
 */
public interface LoginAccountNotificationService {

    void notifyEmailNotRegistered(String normalizedEmail);
}
