package com.example.temperate.service.user.aiconversation.generation.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.id.snowflake.component.HybridSemaphoreIdWorker;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.mapper.ai.AiConversationGenerationPayloadMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.user.aiconversation.billing.AiConversationBillingService;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationCommand;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCreateCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCreationTransactionService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationDispatchEvent;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationObserverStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStart;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationDetachedEvent;
import com.example.temperate.service.user.aiconversation.generation.input.AiConversationGenerationInputCodec;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在短事务内原子创建预扣记录、Generation 和 Payload，并只登记提交后调度事件。
 *
 * <p>附件校验、上下文准备和 RabbitMQ Confirm 均由事务外协作者负责，避免外部 I/O 长时间占用数据库连接。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationCreationTransactionServiceImpl
        implements AiConversationGenerationCreationTransactionService {

    private final AiConversationBillingService billingService;
    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationGenerationPayloadMapper payloadMapper;
    private final HybridSemaphoreIdWorker idWorker;
    private final HybridBase64UrlCodec idCodec;
    private final AiConversationGenerationInputCodec inputCodec;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AiConversationGenerationCreationTransactionServiceImpl(
            AiConversationBillingService billingService,
            AiConversationGenerationMapper generationMapper,
            AiConversationGenerationPayloadMapper payloadMapper,
            HybridSemaphoreIdWorker idWorker,
            HybridBase64UrlCodec idCodec,
            AiConversationGenerationInputCodec inputCodec,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.billingService = Objects.requireNonNull(billingService);
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.payloadMapper = Objects.requireNonNull(payloadMapper);
        this.idWorker = Objects.requireNonNull(idWorker);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.inputCodec = Objects.requireNonNull(inputCodec);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public AiConversationGenerationStart create(
            AiConversationGenerationCreateCommand command) {
        AiConversationReservation reservation = billingService.reserve(
                new AiConversationReservationCommand(
                        command.userId(),
                        command.conversationId(),
                        command.model(),
                        command.idempotencyDigest(),
                        command.estimatedPromptTokens()));
        if (reservation.replay()) {
            AiConversationGeneration existing = generationMapper
                    .findOwnedByIdempotencyDigest(
                            command.idempotencyDigest(), command.userId());
            if (existing == null) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_IDEMPOTENCY_CONFLICT,
                        "相同幂等键属于旧版请求，不能转换为后台生成任务",
                        false);
            }
            AiConversationGenerationStart replay = start(
                    existing,
                    command.modelPublicId(),
                    command.conversationId() == null,
                    true);
            if (existing.getGenerationStatus()
                    == AiConversationGenerationStatus.QUEUED.code()) {
                // 原请求可能在数据库提交后、Rabbit 发布前崩溃；幂等重放顺便补发调度事实，Owner CAS 会拒绝重复调用模型。
                eventPublisher.publishEvent(new AiConversationGenerationDispatchEvent(
                        replay.generationPublicId(), replay.usagePublicId(), command.traceId()));
            }
            return replay;
        }

        OffsetDateTime now = clock.instant().truncatedTo(ChronoUnit.MICROS)
                .atOffset(ZoneOffset.UTC);
        byte[] generationId = idWorker.nextId();
        AiConversationGeneration generation = new AiConversationGeneration();
        generation.setId(generationId);
        generation.setLoginIdentityId(command.userId());
        generation.setConversationId(reservation.conversationId());
        generation.setUsageId(reservation.usageId());
        generation.setIdempotencyKeyDigest(command.idempotencyDigest());
        generation.setModelId(command.model().id());
        generation.setGenerationStatus(AiConversationGenerationStatus.QUEUED.code());
        // 事务提交与首个 SSE Observer 建立之间存在进程崩溃窗口，初始必须按失联处理并让真实 attach 使 epoch 失效。
        generation.setObserverStatus(AiConversationGenerationObserverStatus.DETACHED.code());
        generation.setObserverEpoch(0L);
        generation.setDetachedAt(now);
        generation.setCreatedAt(now);
        generation.setUpdatedAt(now);
        try {
            if (generationMapper.insert(generation) != 1) {
                throw new IllegalStateException("AI Generation insert did not affect one row.");
            }
        } catch (DuplicateKeyException duplicate) {
            // 部分唯一索引是跨实例的最终防线；冲突必须让同一事务中的预扣一起回滚。
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONVERSATION_BUSY,
                    "当前会话已有回答正在生成",
                    true);
        }

        AiConversationGenerationPayload payload = new AiConversationGenerationPayload();
        payload.setGenerationId(generationId);
        payload.setInputText(command.input().text());
        // 图片控制参数与附件共用现有 JSONB 版本化信封，禁止为媒体内容新增字段或把 Base64 写入数据库。
        payload.setInputAttachmentsJson(inputCodec.encode(
                command.input().attachments(), command.imageGeneration()));
        payload.setReasoningEffort(command.reasoningEffort());
        payload.setUpdatedAt(now);
        if (payloadMapper.insert(payload) != 1) {
            throw new IllegalStateException("AI Generation payload insert did not affect one row.");
        }

        AiConversationGenerationStart start = start(
                generation,
                command.modelPublicId(),
                reservation.newConversation(),
                false);
        // 事件由 AFTER_COMMIT 监听器发布；事务回滚时不能留下 Worker 可见的幽灵消息。
        eventPublisher.publishEvent(new AiConversationGenerationDispatchEvent(
                start.generationPublicId(), start.usagePublicId(), command.traceId()));
        eventPublisher.publishEvent(new AiConversationGenerationDetachedEvent(
                start.generationPublicId(), 0L, now, command.traceId()));
        return start;
    }

    private AiConversationGenerationStart start(
            AiConversationGeneration generation,
            String modelPublicId,
            boolean newConversation,
            boolean replay) {
        return new AiConversationGenerationStart(
                idCodec.encode(generation.getId()),
                idCodec.encode(generation.getConversationId()),
                idCodec.encode(generation.getUsageId()),
                modelPublicId,
                newConversation,
                replay);
    }

}
