package com.example.temperate.service.registration.verification.delivery.util.gmail;

import reactor.core.publisher.Mono;

/**
 * 为 Gmail API 调用提供当前可用的 OAuth access token。
 */
@FunctionalInterface
public interface GmailAccessTokenSupplier {

    Mono<String> accessToken();

    /**
     * 401 后由调用方通知供应器丢弃本地缓存；无缓存实现保持空操作即可。
     */
    default void invalidate() {
    }
}
