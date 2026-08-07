package com.example.temperate.service.user.aiconversation.context.event.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventNotification;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

/**
 * 订阅固定上下文事件频道并按会话在本实例扇出，丢失通知由 Redis 快照 revision 恢复。
 */
@Service
public final class RedisAiConversationContextEventSubscriberImpl
        implements AiConversationContextEventSubscriber, MessageListener {

    private final RedisMessageListenerContainer container;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<
            Consumer<AiConversationContextEventNotification>>> listeners =
            new ConcurrentHashMap<>();

    public RedisAiConversationContextEventSubscriberImpl(
            RedisMessageListenerContainer container,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper) {
        this.container = Objects.requireNonNull(container);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @PostConstruct
    void registerTopic() {
        container.addMessageListener(
                this,
                new ChannelTopic(keyFactory.aiConversationContextEventsChannel()));
    }

    @Override
    public AutoCloseable subscribe(
            String conversationPublicId,
            Consumer<AiConversationContextEventNotification> listener) {
        Consumer<AiConversationContextEventNotification> safe =
                Objects.requireNonNull(listener);
        CopyOnWriteArrayList<Consumer<AiConversationContextEventNotification>> bucket =
                listeners.computeIfAbsent(
                        conversationPublicId,
                        ignored -> new CopyOnWriteArrayList<>());
        bucket.add(safe);
        return () -> {
            bucket.remove(safe);
            if (bucket.isEmpty()) {
                listeners.remove(conversationPublicId, bucket);
            }
        };
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            AiConversationContextEventNotification event = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    AiConversationContextEventNotification.class);
            if (event.schemaVersion()
                    != AiConversationContextEventNotification.CURRENT_SCHEMA_VERSION) {
                return;
            }
            listeners.getOrDefault(
                            event.conversationPublicId(),
                            new CopyOnWriteArrayList<>())
                    .forEach(listener -> notifySafely(listener, event));
        } catch (RuntimeException | java.io.IOException ignoredFailure) {
            // Pub/Sub 仅负责唤醒；损坏或丢失通知不会改变 Redis 快照和 PostgreSQL 消息。
        }
    }

    private static void notifySafely(
            Consumer<AiConversationContextEventNotification> listener,
            AiConversationContextEventNotification event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ignoredFailure) {
            // 单个已关闭 SSE 监听器不能阻止同实例其他页面接收状态变化。
        }
    }
}
