package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseActiveRegistry;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationHandle;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 使用并发 Map 保存本实例直接 SSE 上游取消句柄，并通过原子移除保证取消最多交付一次。
 */
@Service
public final class AiConversationDirectResponseActiveRegistryImpl
        implements AiConversationDirectResponseActiveRegistry {

    private final ConcurrentHashMap<String, AiConversationDirectResponseCancellationHandle>
            active = new ConcurrentHashMap<>();

    @Override
    public void register(
            String requestIdentifier,
            AiConversationDirectResponseCancellationHandle cancellationHandle) {
        Objects.requireNonNull(requestIdentifier);
        Objects.requireNonNull(cancellationHandle);
        if (active.putIfAbsent(requestIdentifier, cancellationHandle) != null) {
            throw new IllegalStateException(
                    "AI direct response already has an active owner.");
        }
    }

    @Override
    public boolean cancel(
            String requestIdentifier,
            AiConversationInterruptionSource interruptionSource) {
        Objects.requireNonNull(interruptionSource);
        AiConversationDirectResponseCancellationHandle handle =
                active.remove(requestIdentifier);
        if (handle == null) {
            return false;
        }
        handle.cancel(interruptionSource);
        return true;
    }

    @Override
    public boolean isActive(String requestIdentifier) {
        return active.containsKey(requestIdentifier);
    }

    @Override
    public void remove(
            String requestIdentifier,
            AiConversationDirectResponseCancellationHandle cancellationHandle) {
        active.remove(requestIdentifier, cancellationHandle);
    }
}
