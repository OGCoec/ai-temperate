package com.example.temperate.service.user.aiconversation.image.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewData;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewPublishResult;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageOutputStatusData;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

/**
 * 使用有界生命周期的本地槽位 Broker 转发图片预览；每个输出序号只保留最新一张，不接触持久化设施。
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
    private final Duration detachGrace;
    private final long maximumRetainedBytes;
    private final AtomicLong retainedBytes = new AtomicLong();

    public AiConversationImagePreviewBrokerImpl(
            AiConversationAsyncGenerationProperties properties) {
        this(properties, 268_435_456L);
    }

    @Autowired
    public AiConversationImagePreviewBrokerImpl(
            AiConversationAsyncGenerationProperties properties,
            AiConversationImageGenerationProperties imageProperties) {
        this(properties, Objects.requireNonNull(imageProperties).maximumPreviewRetainedBytes());
    }

    AiConversationImagePreviewBrokerImpl(
            AiConversationAsyncGenerationProperties properties,
            long maximumRetainedBytes) {
        Objects.requireNonNull(properties);
        this.maximumLifetime = properties.maxWorkerDuration()
                .plus(properties.detachGrace())
                .plusMinutes(1);
        this.detachGrace = properties.detachGrace();
        if (maximumRetainedBytes <= 0L) {
            throw new IllegalArgumentException("Preview retained byte limit must be positive.");
        }
        this.maximumRetainedBytes = maximumRetainedBytes;
    }

    @Override
    public AiConversationImagePreviewPublishResult publish(
            String generationPublicId,
            AiConversationGeneratedImage image) {
        Objects.requireNonNull(image);
        return entry(generationPublicId).publishImage(image);
    }

    private static AiConversationStreamEvent previewEvent(
            AiConversationGeneratedImage image) {
        AiConversationImagePreviewData data = new AiConversationImagePreviewData(
                image.imageId(),
                image.phase().name(),
                image.outputIndex(),
                image.partialImageIndex(),
                image.contentType(),
                image.width(),
                image.height(),
                image.base64());
        return new AiConversationStreamEvent("image-preview", data);
    }

    @Override
    public void publishFailure(
            String generationPublicId,
            short outputIndex,
            String reasonCode) {
        if (outputIndex < 0 || outputIndex > 9) {
            throw new IllegalArgumentException("Image output index is out of range.");
        }
        String safeReason = reasonCode == null || reasonCode.isBlank()
                ? "AI_UPSTREAM_STREAM_FAILED"
                : reasonCode;
        entry(generationPublicId).publish(
                outputIndex,
                new AiConversationStreamEvent(
                        "image-output-status",
                        new AiConversationImageOutputStatusData(
                                outputIndex, "FAILED", safeReason)));
    }

    @Override
    public Flux<AiConversationStreamEvent> events(String generationPublicId) {
        return Flux.defer(() -> entry(generationPublicId).events());
    }

    @Override
    public void seal(String generationPublicId) {
        String id = requireGenerationId(generationPublicId);
        Entry current = entries.get(id);
        if (current == null) {
            return;
        }
        Mono.delay(detachGrace).subscribe(unused -> releaseIfSame(id, current));
    }

    @Override
    public void release(String generationPublicId) {
        Entry entry = entries.remove(requireGenerationId(generationPublicId));
        if (entry != null) {
            releaseRetained(entry.complete());
        }
    }

    private Entry entry(String generationPublicId) {
        String id = requireGenerationId(generationPublicId);
        return entries.computeIfAbsent(id, ignored -> {
            Entry created = new Entry(this);
            // 没有观察者或浏览器异常退出时仍必须自动释放大图片，防止本地预览通道长期占用堆内存。
            Mono.delay(maximumLifetime).subscribe(unused -> {
                if (entries.remove(id, created)) {
                    releaseRetained(created.complete());
                }
            });
            return created;
        });
    }

    long retainedBytes() {
        return retainedBytes.get();
    }

    private boolean reserveRetained(long bytes) {
        while (true) {
            long current = retainedBytes.get();
            if (bytes > maximumRetainedBytes - current) {
                return false;
            }
            if (retainedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    private void releaseRetained(long bytes) {
        if (bytes <= 0L) {
            return;
        }
        long remaining = retainedBytes.addAndGet(-bytes);
        if (remaining < 0L) {
            retainedBytes.addAndGet(bytes);
            throw new IllegalStateException("Preview retained byte accounting underflow.");
        }
    }

    private void releaseIfSame(String id, Entry expected) {
        if (entries.remove(id, expected)) {
            releaseRetained(expected.complete());
        }
    }

    private static String requireGenerationId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("generationPublicId must not be blank");
        }
        return value;
    }

    /**
     * 订阅者注册、槽位快照和实时发布共用同一把短锁，确保重连不会丢失注册间隙内的图片事件。
     */
    private static final class Entry {
        private final AiConversationImagePreviewBrokerImpl owner;
        private final ConcurrentMap<Short, Object> latest =
                new ConcurrentHashMap<>();
        private final List<FluxSink<AiConversationStreamEvent>> observers =
                new ArrayList<>();
        private boolean completed;

        private Entry(AiConversationImagePreviewBrokerImpl owner) {
            this.owner = owner;
        }

        private AiConversationImagePreviewPublishResult publishImage(
                AiConversationGeneratedImage image) {
            List<FluxSink<AiConversationStreamEvent>> snapshot;
            boolean retained;
            synchronized (this) {
                if (completed) {
                    return AiConversationImagePreviewPublishResult.ignored();
                }
                retained = replaceRetained(
                        image.outputIndex(), image, image.sizeBytes());
                snapshot = List.copyOf(observers);
            }
            if (snapshot.isEmpty()) {
                return new AiConversationImagePreviewPublishResult(
                        true, retained, 0);
            }
            // 无观察者时不创建 Base64；实时分发只在锁外编码一次并复用给当前观察者。
            AiConversationStreamEvent event = previewEvent(image);
            for (FluxSink<AiConversationStreamEvent> observer : snapshot) {
                observer.next(event);
            }
            return new AiConversationImagePreviewPublishResult(
                    true, retained, snapshot.size());
        }

        private synchronized void publish(
                short outputIndex,
                AiConversationStreamEvent event) {
            if (completed) {
                return;
            }
            replaceRetained(outputIndex, event, 0L);
            for (FluxSink<AiConversationStreamEvent> observer
                    : List.copyOf(observers)) {
                observer.next(event);
            }
        }

        private Flux<AiConversationStreamEvent> events() {
            return Flux.create(observer -> {
                synchronized (this) {
                    if (completed) {
                        observer.complete();
                        return;
                    }
                    observers.add(observer);
                    latest.entrySet().stream()
                            .sorted(Comparator.comparingInt(entry -> entry.getKey()))
                            .map(java.util.Map.Entry::getValue)
                            .map(Entry::streamEvent)
                            .forEach(observer::next);
                }
                observer.onDispose(() -> remove(observer));
            }, FluxSink.OverflowStrategy.LATEST);
        }

        private synchronized void remove(
                FluxSink<AiConversationStreamEvent> observer) {
            observers.remove(observer);
        }

        private synchronized long complete() {
            if (completed) {
                return 0L;
            }
            completed = true;
            long released = latest.values().stream()
                    .mapToLong(Entry::retainedSize)
                    .sum();
            latest.clear();
            for (FluxSink<AiConversationStreamEvent> observer
                    : List.copyOf(observers)) {
                observer.complete();
            }
            observers.clear();
            return released;
        }

        private boolean replaceRetained(
                short outputIndex,
                Object value,
                long valueBytes) {
            Object previous = latest.remove(outputIndex);
            owner.releaseRetained(retainedSize(previous));
            // 达到全实例硬上限时仍向当前观察者实时发送，但不把该大图留在重连缓存中。
            if (valueBytes == 0L || owner.reserveRetained(valueBytes)) {
                latest.put(outputIndex, value);
            }
            return latest.containsKey(outputIndex);
        }

        private static long retainedSize(Object value) {
            return value instanceof AiConversationGeneratedImage image
                    ? image.sizeBytes()
                    : 0L;
        }

        private static AiConversationStreamEvent streamEvent(Object value) {
            if (value instanceof AiConversationGeneratedImage image) {
                return previewEvent(image);
            }
            return (AiConversationStreamEvent) value;
        }
    }
}
