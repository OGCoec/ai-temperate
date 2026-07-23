package com.example.temperate.service.auth.login.notification.impl;

import com.example.temperate.service.auth.login.notification.LoginAccountNotificationService;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 异步发送未注册邮箱提示的登录通知实现。
 *
 * <p>邮件发送失败仅记录受控事件，不得回写或影响已完成的登录验证码流程。</p>
 */
@Service
public final class LoginAccountNotificationServiceImpl
        implements LoginAccountNotificationService {

    private static final System.Logger LOGGER =
            System.getLogger(LoginAccountNotificationServiceImpl.class.getName());

    private final JavaMailSender mailSender;
    private final Executor executor;
    private final String fromAddress;

    public LoginAccountNotificationServiceImpl(
            JavaMailSender mailSender,
            @Qualifier("registrationDeliveryExecutor") Executor executor,
            @Value("${app.registration.mail.from}") String fromAddress) {
        this.mailSender = Objects.requireNonNull(mailSender);
        this.executor = Objects.requireNonNull(executor);
        this.fromAddress = Objects.requireNonNull(fromAddress);
    }

    @Override
    public void notifyEmailNotRegistered(String normalizedEmail) {
        executor.execute(() -> {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(fromAddress);
                message.setTo(normalizedEmail);
                message.setSubject("登录提示");
                message.setText("该邮箱尚未注册，请先完成新用户注册。");
                mailSender.send(message);
            } catch (RuntimeException exception) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "event=login_unregistered_email_notice_failed");
            }
        });
    }
}
