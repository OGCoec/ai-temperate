package com.example.temperate.service.user.aiconversation.generation.worker;

import reactor.core.Disposable;

/**
 * 定义本实例按 Generation 公共 ID 保存上游取消句柄和处理订阅前取消竞态的线程安全边界。
 */
public interface AiConversationGenerationActiveRegistry {

    void register(String generationPublicId, Disposable cancellationHandle);

    boolean cancel(String generationPublicId);

    boolean isActive(String generationPublicId);

    void clear(String generationPublicId);

    void remove(String generationPublicId, Disposable cancellationHandle);
}
