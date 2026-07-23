package com.example.temperate.service.registration.verification.generator;

/**
 * 验证码生成器的抽象。
 *
 * <p>用途：为注册、登录和密码重置流程生成统一格式的验证码，而不让业务编排直接依赖随机数实现。</p>
 */
public interface VerificationCodeGenerator {

    String generate();
}
