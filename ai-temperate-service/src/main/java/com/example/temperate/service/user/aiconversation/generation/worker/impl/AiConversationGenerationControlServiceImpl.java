package com.example.temperate.service.user.aiconversation.generation.worker.impl;

import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.mapper.ai.AiModelUsageDetailMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationClaim;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationControlService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorkItem;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationStage;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 Generation 行锁和预期状态更新完成 Owner 领取；长时间模型调用始终发生在事务外。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationControlServiceImpl
        implements AiConversationGenerationControlService {

    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationGenerationPayloadMapper payloadMapper;
    private final AiModelUsageDetailMapper usageDetailMapper;
    private final AiConversationAsyncGenerationProperties properties;
    private final Clock clock;

    public AiConversationGenerationControlServiceImpl(
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationPayloadMapper payloadMapper,
            AiModelUsageDetailMapper usageDetailMapper,
            AiConversationAsyncGenerationProperties properties,
            Clock clock) {
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.usageDetailMapper = Objects.requireNonNull(usageDetailMapper);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public AiConversationGenerationClaim claim(byte[] generationId) {
        AiConversationGeneration generation = generationMapper.findByIdForUpdate(generationId);
        if (generation == null) {
            return new AiConversationGenerationClaim("MISSING", null);
        }
        AiConversationGenerationStatus status = status(generation.getGenerationStatus());
        if (status == AiConversationGenerationStatus.CANCEL_REQUESTED) {
            return new AiConversationGenerationClaim("CANCELLED_BEFORE_START", workItem(generation));
        }
        if (status != AiConversationGenerationStatus.QUEUED) {
            return new AiConversationGenerationClaim(
                    status == AiConversationGenerationStatus.RUNNING
                            ? "ALREADY_RUNNING" : "ALREADY_TERMINAL",
                    workItem(generation));
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (generationMapper.claimQueued(
                generationId,
                AiConversationGenerationStatus.QUEUED.code(),
                AiConversationGenerationStatus.RUNNING.code(),
                properties.instanceId(),
                now) != 1) {
            throw new IllegalStateException("AI Generation claim CAS did not affect one row.");
        }
        generation.setGenerationStatus(AiConversationGenerationStatus.RUNNING.code());
        generation.setOwnerInstanceId(properties.instanceId());
        generation.setStartedAt(now);
        return new AiConversationGenerationClaim("CLAIMED", workItem(generation));
    }

    @Override
    public AiConversationGenerationWorkItem load(byte[] generationId) {
        AiConversationGeneration generation = generationMapper.findById(generationId);
        return generation == null ? null : workItem(generation);
    }

    @Override
    @Transactional
    public void bindContextCursor(
            byte[] generationId,
            String contextGeneration,
            long ephemeralOrdinal) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        if (payloadMapper.bindContextCursor(
                generationId, contextGeneration, ephemeralOrdinal, now) != 1) {
            throw new IllegalStateException(
                    "AI Generation context cursor was already bound or missing.");
		}
    }

    @Override
    @Transactional
    public void bindUpstreamRequestId(
            byte[] generationId,
            String upstreamRequestId) {
        String normalized = Objects.requireNonNull(upstreamRequestId).trim();
        if (!normalized.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("AI upstream request ID is invalid.");
        }
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        // 创建请求一经受理便冻结其 ID；不同 ID 的再次绑定意味着可能重复创建，必须停止并转人工对账。
        if (payloadMapper.bindUpstreamRequestId(
                generationId, normalized, now) != 1) {
            throw new IllegalStateException(
                    "AI upstream request ID conflicts with the frozen request ID.");
		}
    }

	@Override
	@Transactional
	public void updateVideoStage(
			byte[] generationId,
			AiConversationVideoGenerationStage stage) {
		if (generationMapper.updateVideoStage(
				generationId, Objects.requireNonNull(stage).name()) != 1) {
			throw new IllegalStateException("AI video Generation stage update did not affect one row.");
		}
	}

    private AiConversationGenerationWorkItem workItem(
            AiConversationGeneration generation) {
        AiConversationGenerationPayload payload = payloadMapper.findByGenerationId(
                generation.getId());
        if (payload == null) {
            throw new IllegalStateException("AI Generation payload is missing.");
        }
        var usageDetail = usageDetailMapper.findByUsageId(generation.getUsageId());
        if (usageDetail == null) {
            throw new IllegalStateException("AI Generation usage detail is missing.");
        }
        return new AiConversationGenerationWorkItem(
                generation, payload, usageDetail);
    }

    private static AiConversationGenerationStatus status(int code) {
        for (AiConversationGenerationStatus value : AiConversationGenerationStatus.values()) {
            if (value.code() == code) {
                return value;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }
}
