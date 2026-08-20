package com.example.temperate.service.registration.verification.delivery.util.email;

import com.example.temperate.service.registration.verification.delivery.dto.VerificationPurpose;
import java.util.Objects;

/**
 * 为 Gmail 和 Microsoft Graph 生成完全一致的六位数验证码邮件内容。
 *
 * <p>模板只负责供应商无关的主题和正文，不负责收件地址、OAuth、实际投递或用户输入校验。</p>
 */
public final class VerificationEmailContentFactory {

    private VerificationEmailContentFactory() {
    }

    public static VerificationEmailContent create(
            VerificationPurpose purpose,
            String code) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        if (code == null || !code.matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("code must contain exactly six digits");
        }
        String purposeText = switch (purpose) {
            case REGISTRATION -> "注册";
            case ADMIN_REGISTRATION -> "管理员注册";
            case LOGIN -> "登录";
            case OAUTH_PHONE -> "第三方登录手机号验证";
            case PASSWORD_RESET -> "找回密码";
        };
        return new VerificationEmailContent(
                purposeText + "验证码",
                "您的" + purposeText + "验证码是 " + code + "，5 分钟内有效。");
    }
}
