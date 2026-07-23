package com.example.temperate.model.auth.enums;

/**
 * 表示 SHOPPING_V1 密码策略产生的五档强度等级。
 *
 * <p>该枚举既用于认证领域判断，也作为数据库稳定值持久化；外部客户端不得提交或覆盖该值。</p>
 */
public enum PasswordStrengthLevel {
    NONE(0, "无"),
    WEAK(1, "弱"),
    MEDIUM(2, "中"),
    STRONG(3, "强"),
    VERY_STRONG(4, "很强");

    private final int score;
    private final String label;

    PasswordStrengthLevel(int score, String label) {
        this.score = score;
        this.label = label;
    }

    public int score() {
        return score;
    }

    public String label() {
        return label;
    }

    public boolean isAtLeast(PasswordStrengthLevel minimum) {
        return minimum != null && score >= minimum.score;
    }
}
