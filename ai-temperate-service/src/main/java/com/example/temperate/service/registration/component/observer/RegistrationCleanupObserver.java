package com.example.temperate.service.registration.component.observer;

/**
 * 定义注册事务提交后 Redis 清理重试耗尽时的观测出口。
 */
public interface RegistrationCleanupObserver {

    void cleanupExhausted(int attempts);
}
