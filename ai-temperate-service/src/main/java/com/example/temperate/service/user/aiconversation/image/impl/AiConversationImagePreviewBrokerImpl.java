package com.example.temperate.service.user.aiconversation.image.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewData;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 使用有界生命周期的本地 replay sink 转发图片预览；只重放最新一张，不接触 Redis、RabbitMQ 或数据库。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationImagePreviewBrokerImpl
        implements AiConversationImagePreviewBroker {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration maximumLifetime;

    public AiConversationImagePreviewBrokerImpl(
            AiConversationAsyncGenerationProperties properties) {
        Objects.requireNonNull(properties);
        this.maximumLifetime = properties.maxWorkerDuration()
                .plus(properties.detachGrace())
                .plusMinutes(1);
    }

    @Override
    public void publish(
            String generationPublicId,
            AiConversationGeneratedImage image) {
        Objects.requireNonNull(image);
        Entry entry = entry(generationPublicId);
        AiConversationImagePreviewData data = new AiConversationImagePreviewData(
                image.imageId(),
                image.phase().name(),
                image.index(),
                image.contentType(),
                image.width(),
                image.height(),
                Base64.getEncoder().encodeToString(image.bytes()));
        entry.sink().tryEmitNext(new AiConversationStreamEvent(
                "image-preview", data));
    }

    @Override
    public Flux<AiConversationStreamEvent> events(String generationPublicId) {
        return Flux.defer(() -> entry(generationPublicId).sink().asFlux());
    }

    @Override
    public void release(String generationPublicId) {
        Entry entry = entries.remove(requireGenerationId(generationPublicId));
        if (entry != null) {
            entry.sink().tryEmitComplete();
        }
    }

    private Entry entry(String generationPublicId) {
        String id = requireGenerationId(generationPublicId);
        return entries.computeIfAbsent(id, ignored -> {
            Entry created = new Entry(Sinks.many().replay().limit(1));
            // 没有观察者或浏览器异常退出时仍必须自动释放大图片，防止本地预览通道长期占用堆内存。
            Mono.delay(maximumLifetime).subscribe(unused -> {
                if (entries.remove(id, created)) {
                    created.sink().tryEmitComplete();
                }
            });
            return created;
        });
    }

    private static String requireGenerationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generationPublicId must not be blank");
        }
        return value;
    }

    private record Entry(Sinks.Many<AiConversationStreamEvent> sink) {
    }
}
