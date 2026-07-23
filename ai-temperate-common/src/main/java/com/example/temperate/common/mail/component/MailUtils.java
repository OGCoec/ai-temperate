package com.example.temperate.common.mail.component;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 邮件工具类：异步发送登录验证码
 */
@Component
public class MailUtils {

    private static final Logger log = LoggerFactory.getLogger(MailUtils.class);

    private final JavaMailSender mailSender;

    /**
     * 发件人邮箱（通常为 spring.mail.username）
     */
    private final String fromAddress;

    public MailUtils(JavaMailSender mailSender,
                     @Value("${spring.mail.username}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    /**
     * 异步发送登录验证码邮件
     *
     * @param to   收件人邮箱
     * @param code 验证码
     * @param time 有效时间（分钟）
     */
    //调用AsyncConfig中的mailTaskExecutor线程池
    @Async("mailTaskExecutor")
    public void sendLoginCodeAsync(String toaddress, String code, String time) {
        String content = String.format("您的验证码为：%s，有效期：%s分钟，请及时完成登录", code, time);

        log.debug("正在在线程 [{}] 中向 [{}] 发送登录验证码", Thread.currentThread().getName(), toaddress);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toaddress);
        message.setSubject("登录验证码");
        message.setText(content);
        mailSender.send(message);
    }

    /**
     * 异步发送重置密码验证码邮件
     *
     * @param toaddress 收件人邮箱
     * @param code      验证码
     * @param time      有效时间（分钟）
     */
    @Async("mailTaskExecutor")
    public void sendForgetPasswordCodeAsync(String toaddress, String code, String time) {
        String content = String.format("您正在重置密码，验证码为：%s，有效期：%s分钟。如非本人操作请忽略。", code, time);

        log.debug("正在在线程 [{}] 中向 [{}] 发送重置密码验证码", Thread.currentThread().getName(), toaddress);
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toaddress);
        message.setSubject("重置密码验证码");
        message.setText(content);
        mailSender.send(message);
    }
}

