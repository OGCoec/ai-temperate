package com.example.temperate.service.auth.password.policy;

import com.example.temperate.model.auth.enums.PasswordStrengthLevel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 统一计算注册和密码重置写入前使用的 SHOPPING_V1 五档密码强度。
 *
 * <p>该策略严格保留 Shopping 前端的分级顺序与回落行为，并额外执行 BCrypt 的 72 字节安全边界；
 * 它不负责哈希、持久化、认证或外部错误码映射。</p>
 */
@Component
public final class PasswordStrengthPolicy {

    public static final String POLICY_NAME = "SHOPPING_V1";
    public static final int MAXIMUM_UTF8_BYTES = 72;
    public static final PasswordStrengthLevel MINIMUM_ACCEPTED_LEVEL = PasswordStrengthLevel.MEDIUM;

    private static final Pattern DIGITS_ONLY = Pattern.compile("^[0-9]{7,}$");
    private static final Pattern LOWERCASE_ONLY = Pattern.compile("^[a-z]{7,}$");
    private static final Pattern UPPERCASE_ONLY = Pattern.compile("^[A-Z]{7,}$");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*(),.?\":{}|<>]");

    /**
     * 评估密码的五档等级和 BCrypt 字节可接受性，不执行哈希或持久化。
     */
    public PasswordStrengthAssessment assess(String password) {
        String value = password == null ? "" : password;
        int utf8Bytes = value.getBytes(StandardCharsets.UTF_8).length;
        PasswordStrengthLevel level = classify(value);
        boolean acceptable = level.isAtLeast(MINIMUM_ACCEPTED_LEVEL)
                && utf8Bytes <= MAXIMUM_UTF8_BYTES;
        return new PasswordStrengthAssessment(
                level,
                level.score(),
                utf8Bytes,
                acceptable);
    }

    /**
     * 在密码写入前同时校验确认值、最低强度与 72 字节边界。
     *
     * <p>调用方必须把该方法放在任何流程领取、哈希、缓存变更或数据库写入之前。</p>
     */
    public PasswordStrengthAssessment validateForWrite(
            String password,
            String confirmation) {
        if (!Objects.equals(password, confirmation)) {
            throw new PasswordValidationException(
                    PasswordValidationException.Reason.CONFIRMATION_MISMATCH,
                    "Password confirmation does not match.");
        }
        PasswordStrengthAssessment assessment = assess(password);
        if (!assessment.acceptable()) {
            throw new PasswordValidationException(
                    PasswordValidationException.Reason.STRENGTH_INSUFFICIENT,
                    "Password strength must reach medium and use at most 72 UTF-8 bytes.");
        }
        return assessment;
    }

    private static PasswordStrengthLevel classify(String value) {
        if (value.isEmpty() || value.length() <= 6) {
            return PasswordStrengthLevel.NONE;
        }
        if (DIGITS_ONLY.matcher(value).matches()
                || LOWERCASE_ONLY.matcher(value).matches()
                || UPPERCASE_ONLY.matcher(value).matches()) {
            return PasswordStrengthLevel.WEAK;
        }

        int categories = 0;
        if (LOWERCASE.matcher(value).find()) categories++;
        if (UPPERCASE.matcher(value).find()) categories++;
        if (DIGIT.matcher(value).find()) categories++;
        if (SPECIAL.matcher(value).find()) categories++;

        if (value.length() >= 9 && categories == 4) {
            return PasswordStrengthLevel.VERY_STRONG;
        }
        if (value.length() >= 9 && categories == 3) {
            return PasswordStrengthLevel.STRONG;
        }
        // Shopping V1 明确把其余非单类值回落到中等，包括纯特殊字符和未识别字符。
        return PasswordStrengthLevel.MEDIUM;
    }
}
