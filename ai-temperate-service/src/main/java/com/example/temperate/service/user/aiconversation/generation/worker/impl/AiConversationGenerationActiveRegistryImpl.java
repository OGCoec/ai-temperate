package com.example.temperate.service.user.aiconversation.generation.worker.impl;

import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationActiveRegistry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

/**
 * 使用并发 Map 保存本实例活动上游订阅，并用 pending 集合保证订阅建立前收到取消后立即关闭。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationActiveRegistryImpl
        implements AiConversationGenerationActiveRegistry {

    private final ConcurrentHashMap<String, Disposable> active = new ConcurrentHashMap<>();
    private final Set<String> pendingCancellation = ConcurrentHashMap.newKeySet();

    @Override
    public void register(String generationPublicId, Disposable cancellationHandle) {
        Objects.requireNonNull(generationPublicId);
        Objects.requireNonNull(cancellationHandle);
        Disposable previous = active.putIfAbsent(generationPublicId, cancellationHandle);
        if (previous != null) {
            throw new IllegalStateException("AI Generation already has an active owner.");
        }
        if (pendingCancellation.remove(generationPublicId)) {
            cancellationHandle.dispose();
        }
    }

    @Override
    public boolean cancel(String generationPublicId) {
        Disposable handle = active.get(generationPublicId);
        if (handle != null) {
            handle.dispose();
            return true;
        }
        pendingCancellation.add(generationPublicId);
        return false;
    }

    @Override
    public boolean isActive(String generationPublicId) {
        return active.containsKey(generationPublicId);
    }

    @Override
    public void clear(String generationPublicId) {
        pendingCancellation.remove(generationPublicId);
    }

    @Override
    public void remove(String generationPublicId, Disposable cancellationHandle) {
        active.remove(generationPublicId, cancellationHandle);
        pendingCancellation.remove(generationPublicId);
    }
}
