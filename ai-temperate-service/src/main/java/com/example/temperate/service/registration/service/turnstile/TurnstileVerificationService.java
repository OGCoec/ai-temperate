package com.example.temperate.service.registration.service.turnstile;

/**
 * Cloudflare Turnstile 人机校验的服务边界。
 *
 * <p>用途：验证客户端提交的 Turnstile 响应，并绑定注册流程挑战句柄、来源 IP 与预期动作。</p>
 */
public interface TurnstileVerificationService {

    default void verify(String responseToken, String remoteIp, String challengeHandle) {
        verify(responseToken, remoteIp, challengeHandle, "register");
    }

    void verify(
            String responseToken,
            String remoteIp,
            String challengeHandle,
            String expectedAction);
}
