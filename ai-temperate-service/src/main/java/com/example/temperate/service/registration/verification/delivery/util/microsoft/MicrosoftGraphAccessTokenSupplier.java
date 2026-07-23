package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import reactor.core.publisher.Mono;

/**
 * 为 Microsoft Graph 邮件调用提供可失效并重新获取的短期访问令牌。
 *
 * <p>接口不暴露 refresh token；401 后调用方只清除短期缓存，实际令牌交换由实现统一完成。</p>
 */
@FunctionalInterface
public interface MicrosoftGraphAccessTokenSupplier {

    Mono<String> accessToken();

    default void invalidate() {
        // 无状态测试实现不需要处理缓存失效。
    }
}
