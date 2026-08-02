package com.example.temperate.service.user.aiconversation.generation.observer.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputEvent;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

/**
 * 订阅共享 Generation 频道并按公共 ID 在本实例扇出，丢失通知由 Observer 快照 revision 校准。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class RedisAiConversationGenerationOutputSubscriberImpl
        implements AiConversationGenerationOutputSubscriber, MessageListener {

    private final RedisMessageListenerContainer container;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<AiConversationGenerationOutputEvent>>>
            listeners = new ConcurrentHashMap<>();

    public RedisAiConversationGenerationOutputSubscriberImpl(
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
                new ChannelTopic(keyFactory.aiConversationGenerationEventsChannel()));
    }

    @Override
    public AutoCloseable subscribe(
            String generationPublicId,
            Consumer<AiConversationGenerationOutputEvent> listener) {
        Consumer<AiConversationGenerationOutputEvent> safe = Objects.requireNonNull(listener);
        CopyOnWriteArrayList<Consumer<AiConversationGenerationOutputEvent>> bucket =
                listeners.computeIfAbsent(
                        generationPublicId, ignored -> new CopyOnWriteArrayList<>());
        bucket.add(safe);
        return () -> {
            bucket.remove(safe);
            if (bucket.isEmpty()) {
                listeners.remove(generationPublicId, bucket);
            }
        };
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            AiConversationGenerationOutputEvent event = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    AiConversationGenerationOutputEvent.class);
            if (event.schemaVersion()
                    != AiConversationGenerationOutputEvent.CURRENT_SCHEMA_VERSION) {
                return;
            }
            listeners.getOrDefault(
                            event.generationPublicId(),
                            new CopyOnWriteArrayList<>())
                    .forEach(listener -> {
                        try {
                            listener.accept(event);
                        } catch (RuntimeException ignoredListenerFailure) {
                            // 一个已关闭或损坏的 SSE 观察者不能阻止同实例其他页面收到同一 revision。
                        }
                    });
        } catch (RuntimeException | java.io.IOException ignoredFailure) {
            // Pub/Sub 是易失唤醒；损坏或丢失事件由下次快照 revision 恢复，不能改变资金终态。
        }
    }
}
