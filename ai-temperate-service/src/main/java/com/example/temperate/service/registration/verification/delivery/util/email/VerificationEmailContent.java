package com.example.temperate.service.registration.verification.delivery.util.email;

/**
 * 承载不同邮箱供应商共用的验证码邮件主题和纯文本正文。
 *
 * <p>该值对象不包含收件地址或供应商凭据，只在一次投递调用的短暂内存边界内携带验证码正文。</p>
 */
public record VerificationEmailContent(
        String subject,
        String body) {

    public VerificationEmailContent {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
    }
}
