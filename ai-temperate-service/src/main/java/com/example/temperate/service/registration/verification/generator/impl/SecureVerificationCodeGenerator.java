package com.example.temperate.service.registration.verification.generator.impl;

import com.example.temperate.service.registration.verification.generator.VerificationCodeGenerator;
import java.security.SecureRandom;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 基于 {@link SecureRandom} 的六位数字验证码生成器。
 *
 * <p>用途：生成固定宽度的六位验证码。</p>
 *
 * <p>安全原理：使用密码学安全随机源而非伪随机时间种子，避免验证码序列被低成本预测。</p>
 */
@Component
public final class SecureVerificationCodeGenerator implements VerificationCodeGenerator {

    private final SecureRandom secureRandom;

    public SecureVerificationCodeGenerator() {
        this(new SecureRandom());
    }

    SecureVerificationCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public String generate() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
    }
}
