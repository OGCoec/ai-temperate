package com.example.temperate.service.user.aiconversation.generation.cancellation.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCancelSource;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationObserverStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationEvent;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationResult;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 锁定 Generation 并只保留第一次可信取消来源；DETACHED 本身不会进入本服务，只有到期检查才可请求取消。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationCancellationServiceImpl
        implements AiConversationGenerationCancellationService {

    private static final List<Integer> CANCELLABLE = List.of(
            AiConversationGenerationStatus.QUEUED.code(),
            AiConversationGenerationStatus.RUNNING.code());

    private final AiConversationGenerationMapper generationMapper;
    private final HybridBase64UrlCodec idCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final AiConversationMetrics metrics;

    public AiConversationGenerationCancellationServiceImpl(
            AiConversationGenerationMapper generationMapper,
            HybridBase64UrlCodec idCodec,
            ApplicationEventPublisher eventPublisher,
            AiConversationMetrics metrics,
            Clock clock) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public AiConversationGenerationCancellationResult requestUserStop(
            long userId,
            byte[] generationId) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(generationId);
        if (generation == null || generation.getLoginIdentityId() != userId) {
            throw notFound();
        }
        return request(generation, AiConversationGenerationCancelSource.USER_STOP, currentTraceId());
    }

    @Override
    @Transactional
    public AiConversationGenerationCancellationResult requestAdminCancel(
            byte[] generationId) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(generationId);
        if (generation == null) {
            throw notFound();
        }
        return request(generation, AiConversationGenerationCancelSource.ADMIN_CANCEL, currentTraceId());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiConversationGenerationCancellationResult requestDetachedTimeout(
            byte[] generationId,
            long observerEpoch,
            OffsetDateTime detachedAt,
            String traceId) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(generationId);
        if (generation == null) {
            return new AiConversationGenerationCancellationResult(
                    "ALREADY_TERMINAL", idCodec.encode(generationId), null);
        }
        boolean stale = generation.getObserverStatus()
                        != AiConversationGenerationObserverStatus.DETACHED.code()
                || generation.getObserverEpoch() != observerEpoch
                || !sameInstant(generation.getDetachedAt(), detachedAt);
        if (stale) {
            metrics.detachCheck("stale");
            return new AiConversationGenerationCancellationResult(
                    "STALE_DETACH_CHECK", idCodec.encode(generationId), null);
        }
        return request(
                generation,
                AiConversationGenerationCancelSource.CLIENT_EXIT_TIMEOUT,
                traceId);
    }

    private AiConversationGenerationCancellationResult request(
            AiConversationGeneration generation,
            AiConversationGenerationCancelSource source,
            String traceId) {
        String publicId = idCodec.encode(generation.getId());
        AiConversationGenerationStatus status = status(generation.getGenerationStatus());
        if (status.terminal()
                || status == AiConversationGenerationStatus.TERMINAL_PENDING_BILLING) {
            return new AiConversationGenerationCancellationResult(
                    "ALREADY_TERMINAL", publicId, generation.getCancelSource());
        }
        if (status == AiConversationGenerationStatus.CANCEL_REQUESTED) {
            return new AiConversationGenerationCancellationResult(
                    "CANCEL_REQUESTED", publicId, generation.getCancelSource());
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (generationMapper.requestCancellation(
                generation.getId(),
                CANCELLABLE,
                AiConversationGenerationStatus.CANCEL_REQUESTED.code(),
                source.name(),
                now) != 1) {
            throw new IllegalStateException("AI Generation cancellation CAS did not affect one row.");
        }
        eventPublisher.publishEvent(new AiConversationGenerationCancellationEvent(
                publicId,
                source.name(),
                1,
                generation.getOwnerInstanceId(),
                safeTraceId(traceId)));
        metrics.generationCancelRequested();
        if (source == AiConversationGenerationCancelSource.CLIENT_EXIT_TIMEOUT) {
            metrics.detachCheck("expired");
        }
        return new AiConversationGenerationCancellationResult(
                "CANCEL_REQUESTED", publicId, source.name());
    }

    private static AiConversationGenerationStatus status(int code) {
        for (AiConversationGenerationStatus status : AiConversationGenerationStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }

    private static AiConversationException notFound() {
        return new AiConversationException(
                AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                "生成任务不存在或不可用",
                false);
    }

    private static String currentTraceId() {
        return safeTraceId(MDC.get("traceId"));
    }

    private static String safeTraceId(String traceId) {
        return traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")
                ? "unavailable"
                : traceId;
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null
                && left.toInstant().equals(right.toInstant());
    }
}
