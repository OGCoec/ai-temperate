package com.example.temperate.service.auth.passwordreset.notification.impl;

import com.example.temperate.service.auth.passwordreset.notification.PasswordResetNotificationService;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 异步发送密码重置相关安全邮件的通知实现。
 *
 * <p>邮件投递失败只记录受控事件，不能回滚已提交的密码变更或改变重置流程的安全判断。</p>
 */
@Service
public final class PasswordResetNotificationServiceImpl
        implements PasswordResetNotificationService {

    private static final System.Logger LOGGER =
            System.getLogger(PasswordResetNotificationServiceImpl.class.getName());

    private final JavaMailSender mailSender;
    private final Executor executor;
    private final String fromAddress;

    public PasswordResetNotificationServiceImpl(
            JavaMailSender mailSender,
            @Qualifier("registrationDeliveryExecutor") Executor executor,
            @Value("${app.registration.mail.from}") String fromAddress) {
        this.mailSender = Objects.requireNonNull(mailSender);
        this.executor = Objects.requireNonNull(executor);
        this.fromAddress = Objects.requireNonNull(fromAddress);
    }

    @Override
    public void notifyEmailNotRegistered(String normalizedEmail) {
        send(
                normalizedEmail,
                "找回密码提示",
                "该邮箱尚未注册，无需重置密码。",
                "password_reset_unregistered_email_notice_failed");
    }

    @Override
    public void notifyPasswordChanged(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return;
        }
        send(
                normalizedEmail,
                "密码已变更",
                "您的账号密码已成功变更。如非本人操作，请立即联系平台客服。",
                "password_changed_security_notice_failed");
    }

    private void send(String recipient, String subject, String text, String failureEvent) {
        try {
            executor.execute(() -> {
                try {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setFrom(fromAddress);
                    message.setTo(recipient);
                    message.setSubject(subject);
                    message.setText(text);
                    mailSender.send(message);
                } catch (RuntimeException exception) {
                    LOGGER.log(System.Logger.Level.WARNING, "event=" + failureEvent);
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "event=" + failureEvent);
        }
    }
}
