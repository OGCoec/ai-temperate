package com.example.temperate.service.humanverification;

import java.util.Set;

/**
 * 携带一次服务端人机验证所需的响应 Token、规范客户端 IP、业务挑战标识和预期动作。
 *
 * <p>该对象只在当前响应式调用链中短暂存在，禁止缓存、持久化或输出原始响应 Token。
 */
public record HumanVerificationCommand(
        String responseToken,
        String canonicalClientIp,
        String challengeId,
        String expectedAction) {

    private static final Set<String> TURNSTILE_ACTIONS =
            Set.of("register", "login", "password_reset", "oauth_phone");

    /**
     * 创建普通用户 Turnstile 校验命令，并在进入供应商实现前锁定允许的业务动作集合。
     *
     * @param responseToken 前端取得的一次性 Turnstile 响应
     * @param canonicalClientIp 服务端解析后的规范客户端 IP
     * @param challengeId 当前业务 Flow 的挑战标识
     * @param expectedAction 只允许注册、验证码登录、密码重置或 OAuth 补手机动作
     * @return 不包含可空 action 的统一校验命令
     */
    public static HumanVerificationCommand turnstile(
            String responseToken,
            String canonicalClientIp,
            String challengeId,
            String expectedAction) {
        if (!TURNSTILE_ACTIONS.contains(expectedAction)) {
            throw new IllegalArgumentException("Unsupported Turnstile action.");
        }
        return new HumanVerificationCommand(
                responseToken,
                canonicalClientIp,
                challengeId,
                expectedAction);
    }

    /**
     * 创建管理员 hCaptcha 校验命令；hCaptcha 不使用 action，但统一字段必须保持非空。
     *
     * @param responseToken 前端取得的一次性 hCaptcha 响应
     * @param canonicalClientIp 服务端解析后的规范客户端 IP
     * @param challengeId 当前管理员 Flow 的挑战标识
     * @return expectedAction 固定为空字符串的统一校验命令
     */
    public static HumanVerificationCommand hcaptcha(
            String responseToken,
            String canonicalClientIp,
            String challengeId) {
        return new HumanVerificationCommand(
                responseToken,
                canonicalClientIp,
                challengeId,
                "");
    }

    public HumanVerificationCommand {
        if (expectedAction == null) {
            throw new IllegalArgumentException("Human verification action must not be null.");
        }
    }

    @Override
    public String toString() {
        return "HumanVerificationCommand[redacted]";
    }
}
