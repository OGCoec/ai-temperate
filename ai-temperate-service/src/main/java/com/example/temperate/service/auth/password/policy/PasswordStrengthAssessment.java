package com.example.temperate.service.auth.password.policy;

import com.example.temperate.model.auth.enums.PasswordStrengthLevel;

/**
 * 承载一次密码强度计算的等级、分数和字节边界结果。
 *
 * <p>该记录不保存明文密码，只允许在注册和密码重置的写入调用链中短暂传递评估结果。</p>
 */
public record PasswordStrengthAssessment(
        PasswordStrengthLevel level,
        int score,
        int utf8Bytes,
        boolean acceptable) {
}
