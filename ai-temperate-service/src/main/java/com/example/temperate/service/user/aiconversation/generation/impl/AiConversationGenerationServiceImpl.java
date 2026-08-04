package com.example.temperate.service.user.aiconversation.generation.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCreateCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCreationTransactionService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationObserverStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStart;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationView;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 在事务外完成模型、附件和 Redis 上下文准备，再委托短事务原子创建预扣、Generation 与 Payload。
 *
 * <p>模型调用和 RabbitMQ 发布都不在数据库事务中执行；相同 HMAC 摘要只返回既有 Generation。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationServiceImpl
        implements AiConversationGenerationService {

    private static final List<Integer> ACTIVE_STATUSES = List.of(
            AiConversationGenerationStatus.QUEUED.code(),
            AiConversationGenerationStatus.RUNNING.code(),
            AiConversationGenerationStatus.CANCEL_REQUESTED.code(),
            AiConversationGenerationStatus.TERMINAL_PENDING_BILLING.code());

    private final AiModelCacheService modelCacheService;
    private final AiConversationAttachmentService attachmentService;
    private final AiConversationContextService contextService;
    private final AiConversationGenerationCreationTransactionService creationTransactionService;
    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationIdempotencyHasher idempotencyHasher;
    private final AiConversationImageProfileService imageProfileService;
    private final AiConversationImageGenerationProperties imageProperties;
    private final HybridBase64UrlCodec hybridIdCodec;
    private final PublicIdCodec publicIdCodec;

    public AiConversationGenerationServiceImpl(
            AiModelCacheService modelCacheService,
            AiConversationAttachmentService attachmentService,
            AiConversationContextService contextService,
            AiConversationGenerationCreationTransactionService creationTransactionService,
            AiConversationGenerationMapper generationMapper,
            AiConversationIdempotencyHasher idempotencyHasher,
            AiConversationImageProfileService imageProfileService,
            AiConversationImageGenerationProperties imageProperties,
            HybridBase64UrlCodec hybridIdCodec,
            PublicIdCodec publicIdCodec) {
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.contextService = Objects.requireNonNull(contextService);
        this.creationTransactionService = Objects.requireNonNull(creationTransactionService);
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.idempotencyHasher = Objects.requireNonNull(idempotencyHasher);
        this.imageProfileService = Objects.requireNonNull(imageProfileService);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.hybridIdCodec = Objects.requireNonNull(hybridIdCodec);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public AiConversationGenerationStart create(AiConversationResponseCommand command) {
        Objects.requireNonNull(command);
        AiModelCacheEntry model = requiredModel(command.modelPublicId());
        AiConversationImageGenerationOptions imageGeneration =
                resolveImageGeneration(command, model);
        List<AiConversationAttachment> attachments = attachmentService.validateTemporaryInputs(
                command.userPublicId(), command.input().uploadReferences());
        AiConversationContent validatedInput = command.input().validated(attachments);
        AiConversationPromptSnapshot preliminary = command.conversationId() == null
                ? contextService.prepareNew(model, validatedInput)
                : contextService.prepare(
                        command.conversationId(),
                        hybridIdCodec.encode(command.conversationId()),
                        model,
                        validatedInput);
        byte[] digest = idempotencyHasher.digest(command.userId(), command.idempotencyKey());
        return creationTransactionService.create(new AiConversationGenerationCreateCommand(
                command.userId(),
                command.conversationId(),
                command.modelPublicId(),
                model,
                command.reasoningEffort().level(),
                validatedInput,
                imageGeneration,
                digest,
                preliminary.estimatedPromptTokens(),
                currentTraceId()));
    }

    private AiConversationImageGenerationOptions resolveImageGeneration(
            AiConversationResponseCommand command,
            AiModelCacheEntry model) {
        boolean imageModel = model.capabilities().contains(
                AiModelCapabilityCode.IMAGE_GENERATION);
        if (!imageModel && command.imageGeneration() == null) {
            return null;
        }
        if (!imageProperties.enabled()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "图片生成功能当前未启用。",
                    true);
        }
        if (!imageModel
                || command.imageGeneration() == null
                || command.webSearchMode()
                        != com.example.temperate.service.user.aiconversation.response
                                .AiConversationWebSearchMode.OFF
                || command.input().text().isBlank()
                || !command.input().uploadReferences().isEmpty()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "图片模型只接受不带附件的文字生成图片请求。",
                    false);
        }
        AiConversationImageProfile profile = imageProfileService.required(
                model.modelName(),
                command.reasoningEffort(),
                command.imageGeneration().aspect());
        return AiConversationImageGenerationOptions.from(
                command.imageGeneration().aspect(), profile);
    }

    @Override
    public AiConversationGenerationView getOwned(long userId, byte[] generationId) {
        AiConversationGeneration generation = generationMapper.findOwned(generationId, userId);
        if (generation == null) {
            throw notFound();
        }
        return view(generation);
    }

    @Override
    public AiConversationGenerationView getOwnedByIdempotency(
            long userId,
            UUID idempotencyKey) {
        byte[] digest = idempotencyHasher.digest(userId, idempotencyKey);
        AiConversationGeneration generation = generationMapper.findOwnedByIdempotencyDigest(
                digest, userId);
        if (generation == null) {
            throw notFound();
        }
        return view(generation);
    }

    @Override
    public List<AiConversationGenerationView> listActiveOwned(long userId) {
        return generationMapper.findActiveOwned(userId, ACTIVE_STATUSES, 64)
                .stream()
                .map(this::view)
                .toList();
    }

    private AiConversationGenerationView view(AiConversationGeneration generation) {
        return new AiConversationGenerationView(
                hybridIdCodec.encode(generation.getId()),
                hybridIdCodec.encode(generation.getConversationId()),
                hybridIdCodec.encode(generation.getUsageId()),
                generationStatus(generation.getGenerationStatus()).name(),
                observerStatus(generation.getObserverStatus()).name(),
                generation.getObserverEpoch(),
                generation.getCancelSource(),
                generation.getTerminalType(),
                generation.getTerminalReason(),
                generation.getTerminalVersion(),
                generation.getCreatedAt(),
                generation.getUpdatedAt());
    }

    private AiModelCacheEntry requiredModel(String modelPublicId) {
        long modelId = publicIdCodec.decode(modelPublicId);
        return modelCacheService.getOrLoadEnabledSnapshot().models().stream()
                .filter(candidate -> candidate.id() == modelId)
                .findFirst()
                .orElseThrow(() -> new AiConversationException(
                        AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                        "所选模型当前不可用",
                        true));
    }

    private static AiConversationGenerationStatus generationStatus(Integer code) {
        for (AiConversationGenerationStatus status : AiConversationGenerationStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown AI Generation status.");
    }

    private static AiConversationGenerationObserverStatus observerStatus(Integer code) {
        for (AiConversationGenerationObserverStatus status : AiConversationGenerationObserverStatus.values()) {
            if (status.code() == code) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown AI Generation observer status.");
    }

    private static AiConversationException notFound() {
        return new AiConversationException(
                AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                "生成任务不存在或不可用",
                false);
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "unavailable" : traceId;
    }
}
