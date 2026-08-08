package com.example.temperate.service.user.aiconversation.generation.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationGenerationMapper;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.ProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.TokenReservationMetering;
import com.example.temperate.service.user.aiconversation.billing.VideoProviderCostReservationMetering;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionCoordinator;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionRequestResult;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
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
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategyRegistry;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoCostEstimator;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationRequest;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoInputMetadata;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMetadataProbeCommand;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMetadataService;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoModelProfile;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoProfileService;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private static final Set<String> SUPPORTED_EDIT_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp");

    private final AiModelCacheService modelCacheService;
    private final AiConversationAttachmentService attachmentService;
    private final AiConversationContextService contextService;
    private final AiConversationGenerationCreationTransactionService creationTransactionService;
    private final AiConversationGenerationMapper generationMapper;
    private final AiConversationIdempotencyHasher idempotencyHasher;
    private final AiConversationImageProfileService imageProfileService;
    private final AiConversationImageGenerationProperties imageProperties;
    private final AiConversationVideoProfileService videoProfileService;
    private final AiConversationVideoCostEstimator videoCostEstimator;
    private final AiConversationVideoMetadataService videoMetadataService;
    private final AiConversationVideoGenerationProperties videoProperties;
    private final AiConversationStreamingStrategyRegistry strategyRegistry;
    private final HybridBase64UrlCodec hybridIdCodec;
    private final PublicIdCodec publicIdCodec;
    private final AiConversationCompactionCoordinator compactionCoordinator;

    public AiConversationGenerationServiceImpl(
            AiModelCacheService modelCacheService,
            AiConversationAttachmentService attachmentService,
            AiConversationContextService contextService,
            AiConversationGenerationCreationTransactionService creationTransactionService,
            AiConversationGenerationMapper generationMapper,
            AiConversationIdempotencyHasher idempotencyHasher,
            AiConversationImageProfileService imageProfileService,
            AiConversationImageGenerationProperties imageProperties,
            AiConversationVideoProfileService videoProfileService,
            AiConversationVideoCostEstimator videoCostEstimator,
            AiConversationVideoMetadataService videoMetadataService,
            AiConversationVideoGenerationProperties videoProperties,
            AiConversationStreamingStrategyRegistry strategyRegistry,
            HybridBase64UrlCodec hybridIdCodec,
            PublicIdCodec publicIdCodec,
            AiConversationCompactionCoordinator compactionCoordinator) {
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.contextService = Objects.requireNonNull(contextService);
        this.creationTransactionService = Objects.requireNonNull(creationTransactionService);
        this.generationMapper = Objects.requireNonNull(generationMapper);
        this.idempotencyHasher = Objects.requireNonNull(idempotencyHasher);
        this.imageProfileService = Objects.requireNonNull(imageProfileService);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.videoProfileService = Objects.requireNonNull(videoProfileService);
        this.videoCostEstimator = Objects.requireNonNull(videoCostEstimator);
        this.videoMetadataService = Objects.requireNonNull(videoMetadataService);
        this.videoProperties = Objects.requireNonNull(videoProperties);
        this.strategyRegistry = Objects.requireNonNull(strategyRegistry);
        this.hybridIdCodec = Objects.requireNonNull(hybridIdCodec);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator);
    }

    @Override
    public AiConversationGenerationStart create(AiConversationResponseCommand command) {
        Objects.requireNonNull(command);
        AiModelCacheEntry model = requiredModel(command.modelPublicId());
        AiModelProvider provider = AiModelProvider.fromVendor(model.vendor());
        provider.validateReasoningEffort(command.reasoningEffort());
        List<AiConversationAttachment> attachments = attachmentService.validateTemporaryInputs(
                command.userPublicId(), command.input().uploadReferences());
        AiConversationContent validatedInput = command.input().validated(attachments);
		AiConversationImageGenerationOptions imageGeneration =
				command.videoGeneration() == null
						? resolveImageGeneration(command, model, provider, attachments)
						: null;
        AiConversationVideoGenerationOptions videoGeneration =
                resolveVideoGeneration(command, model, provider, attachments);
        AiConversationStreamingProtocol protocol = videoGeneration != null
                ? AiConversationStreamingProtocol.VIDEOS_GENERATION
                : imageGeneration != null
                ? AiConversationStreamingProtocol.IMAGES_GENERATION
                : command.webSearchMode()
                        == com.example.temperate.service.user.aiconversation.response
                                .AiConversationWebSearchMode.OFF
                        ? AiConversationStreamingProtocol.CHAT_COMPLETIONS
                        : AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH;
        AiConversationStreamingStrategy strategy = strategyRegistry.getRequired(
                provider, protocol);
        AiConversationPromptSnapshot preliminary = command.conversationId() == null
                ? contextService.prepareNew(model, validatedInput)
                : contextService.prepare(
                        command.conversationId(),
                        hybridIdCodec.encode(command.conversationId()),
                        model,
                        validatedInput);
        byte[] digest = idempotencyHasher.digest(command.userId(), command.idempotencyKey());
        AiConversationReservationMetering metering = reservationMetering(
                strategy,
                model,
                preliminary.estimatedPromptTokens(),
                imageGeneration,
                videoGeneration);
        return creationTransactionService.create(new AiConversationGenerationCreateCommand(
                command.userId(),
                command.conversationId(),
                command.modelPublicId(),
                model,
                command.reasoningEffort().level(),
                validatedInput,
                imageGeneration,
                videoGeneration,
                command.webSearchMode(),
                digest,
                metering,
                currentTraceId()));
    }

    @Override
    public Mono<AiConversationGenerationStart> createAsync(
            AiConversationResponseCommand command) {
        return Mono.defer(() -> {
            try {
                return Mono.just(create(command));
            } catch (AiConversationException failure) {
                if (failure.code() != AiConversationErrorCode.AI_CONTEXT_TOO_LARGE
                        || command.conversationId() == null) {
                    return Mono.error(failure);
                }
                String conversationPublicId = hybridIdCodec.encode(
                        command.conversationId());
                AiConversationCompactionRequestResult request =
                        compactionCoordinator.request(
                                command.conversationId(),
                                conversationPublicId,
                                publicIdCodec.decode(command.modelPublicId()),
                                AiConversationCompactionTrigger.HARD_LIMIT_WAIT);
                if (request.operation() == null) {
                    return Mono.error(failure);
                }
                return compactionCoordinator.awaitTerminal(
                                conversationPublicId,
                                request.operation().operationPublicId())
                        .then(Mono.fromCallable(() -> create(command))
                                .subscribeOn(Schedulers.boundedElastic()));
            }
        });
    }

    private AiConversationImageGenerationOptions resolveImageGeneration(
            AiConversationResponseCommand command,
            AiModelCacheEntry model,
            AiModelProvider provider,
            List<AiConversationAttachment> attachments) {
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
                || command.input().text().isBlank()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "图片请求参数与模型能力不匹配。",
                    false);
        }
        AiConversationImageAction action = attachments.isEmpty()
                ? AiConversationImageAction.GENERATE
                : AiConversationImageAction.EDIT;
        boolean editableModel = model.capabilities().contains(
                AiModelCapabilityCode.IMAGE_EDIT);
        if ((action == AiConversationImageAction.EDIT
                || command.imageGeneration().outputCount() > 1)
                && !editableModel) {
            throw invalidImageRequest("所选模型不支持图片编辑或多图输出。");
        }
        if (action == AiConversationImageAction.EDIT) {
            validateEditAttachments(provider, attachments);
        }
        AiConversationImageProfile profile = imageProfileService.required(
                provider,
                model.modelName(),
                command.reasoningEffort(),
                command.imageGeneration().aspect());
        return AiConversationImageGenerationOptions.from(
                command.imageGeneration().aspect(),
                profile,
                action,
                command.imageGeneration().outputCount());
    }

    private AiConversationVideoGenerationOptions resolveVideoGeneration(
            AiConversationResponseCommand command,
            AiModelCacheEntry model,
            AiModelProvider provider,
            List<AiConversationAttachment> attachments) {
        boolean videoModel = videoProfileService.supports(
                        provider, model.modelName())
                || model.capabilities().stream().anyMatch(capability ->
                        capability == AiModelCapabilityCode.VIDEO_GENERATION
                                || capability == AiModelCapabilityCode.VIDEO_EDIT
                                || capability == AiModelCapabilityCode.VIDEO_EXTENSION);
        if (!videoModel && command.videoGeneration() == null) {
            return null;
        }
        if (!videoProperties.enabled()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "视频生成功能当前未启用。",
                    true);
        }
        AiConversationVideoGenerationRequest request = command.videoGeneration();
        if (!videoModel
                || request == null
                || command.imageGeneration() != null
                || command.webSearchMode()
                        != com.example.temperate.service.user.aiconversation.response
                                .AiConversationWebSearchMode.OFF
                || command.input().text().isBlank()) {
            throw invalidVideoRequest("视频请求参数与模型能力不匹配。");
        }

        validateVideoCapability(model, request.mode());
        List<AiConversationAttachment> selected = selectVideoAttachments(
                attachments, request.inputAttachmentPublicIds());
        int durationSeconds = request.durationSeconds() == null
                ? 0
                : request.durationSeconds();
        AiConversationVideoResolution resolution = request.resolution();
        AiConversationVideoInputMetadata inputMetadata = null;

        switch (request.mode()) {
            case TEXT_TO_VIDEO -> requireVideoInputShape(
                    selected, 0, request.durationSeconds(), resolution,
                    request.aspectRatio());
            case IMAGE_TO_VIDEO -> {
                requireVideoInputShape(
                        selected, 1, request.durationSeconds(), resolution,
                        request.aspectRatio());
                validateImageVideoInputs(selected);
            }
            case REFERENCE_TO_VIDEO -> {
                if (selected.isEmpty() || selected.size() > 7) {
                    throw invalidVideoRequest("参考图视频必须选择一至七张图片。");
                }
                requireGeneratedVideoControls(
                        request.durationSeconds(), resolution, request.aspectRatio());
                validateImageVideoInputs(selected);
            }
            case VIDEO_EDIT, VIDEO_EXTEND -> {
                validateVideoOperationControls(request);
                AiConversationAttachment video = requiredMp4Input(selected);
                // 输入视频时长与编码由 FC 读取，主业务进程只发送小型探测 JSON，绝不下载媒体字节。
				try {
					inputMetadata = videoMetadataService.probe(
							new AiConversationVideoMetadataProbeCommand(
									attachmentService.resolveModelUrl(video),
									video.contentType(),
									videoProperties.functionCompute().maximumVideoBytes()));
				} catch (RuntimeException probeFailure) {
					// 探测失败只向客户端暴露稳定校验错误，禁止传播 FC、OSS 或签名 URL 细节。
					throw invalidVideoRequest("输入视频无法完成可信媒体校验。");
				}
                resolution = inheritedResolution(inputMetadata);
            }
        }

        try {
            AiConversationVideoGenerationOptions options =
                    new AiConversationVideoGenerationOptions(
                            request.mode(),
                            durationSeconds,
                            resolution,
                            request.aspectRatio(),
                            request.inputAttachmentPublicIds(),
                            inputMetadata == null ? 0L : inputMetadata.durationMillis(),
                            inputMetadata == null ? 0 : inputMetadata.width(),
                            inputMetadata == null ? 0 : inputMetadata.height(),
                            inputMetadata == null ? null : inputMetadata.codec());
            videoProfileService.required(
                    provider, model.modelName(), options.mode(), options.resolution());
            return options;
        } catch (IllegalArgumentException exception) {
            throw invalidVideoRequest(exception.getMessage());
        }
    }

    private static void validateVideoCapability(
            AiModelCacheEntry model,
            AiConversationVideoMode mode) {
        boolean supported = switch (mode) {
            case TEXT_TO_VIDEO, IMAGE_TO_VIDEO, REFERENCE_TO_VIDEO ->
                    model.capabilities().contains(AiModelCapabilityCode.VIDEO_GENERATION);
            case VIDEO_EDIT ->
                    model.capabilities().contains(AiModelCapabilityCode.VIDEO_EDIT)
                            && model.capabilities().contains(
                                    AiModelCapabilityCode.VIDEO_INPUT);
            case VIDEO_EXTEND ->
                    model.capabilities().contains(AiModelCapabilityCode.VIDEO_EXTENSION)
                            && model.capabilities().contains(
                                    AiModelCapabilityCode.VIDEO_INPUT);
        };
        if (!supported) {
            throw invalidVideoRequest("所选模型不支持该视频模式。");
        }
    }

    private static List<AiConversationAttachment> selectVideoAttachments(
            List<AiConversationAttachment> attachments,
            List<String> publicIds) {
        LinkedHashMap<String, AiConversationAttachment> byId = new LinkedHashMap<>();
        for (AiConversationAttachment attachment : attachments) {
            byId.put(attachment.attachmentId(), attachment);
        }
        HashSet<String> uniqueIds = new HashSet<>(publicIds);
        if (uniqueIds.size() != publicIds.size()
                || publicIds.size() != attachments.size()) {
            throw invalidVideoRequest(
                    "视频输入必须且只能引用本次请求已授权的全部附件。");
        }
        return publicIds.stream()
                .map(publicId -> {
                    AiConversationAttachment attachment = byId.get(publicId);
                    if (attachment == null) {
                        throw invalidVideoRequest("视频输入附件不存在或不属于当前请求。");
                    }
                    return attachment;
                })
                .toList();
    }

    private static void requireVideoInputShape(
            List<AiConversationAttachment> selected,
            int expectedCount,
            Integer durationSeconds,
            AiConversationVideoResolution resolution,
            com.example.temperate.service.user.aiconversation.video
                    .AiConversationVideoAspectRatio aspectRatio) {
        if (selected.size() != expectedCount) {
            throw invalidVideoRequest("视频模式的输入附件数量不正确。");
        }
        requireGeneratedVideoControls(durationSeconds, resolution, aspectRatio);
    }

    private static void requireGeneratedVideoControls(
            Integer durationSeconds,
            AiConversationVideoResolution resolution,
            com.example.temperate.service.user.aiconversation.video
                    .AiConversationVideoAspectRatio aspectRatio) {
        if (durationSeconds == null || resolution == null || aspectRatio == null) {
            throw invalidVideoRequest("普通视频生成必须指定秒数、清晰度和画幅。");
        }
    }

    private static void validateImageVideoInputs(
            List<AiConversationAttachment> attachments) {
        boolean invalid = attachments.stream().anyMatch(attachment ->
                attachment.category() != AiConversationAttachmentCategory.IMAGE);
        if (invalid) {
            throw invalidVideoRequest("图片或参考图生成只允许使用图片附件。");
        }
    }

    private static void validateVideoOperationControls(
            AiConversationVideoGenerationRequest request) {
        if (request.resolution() != null || request.aspectRatio() != null) {
            throw invalidVideoRequest("视频编辑和延长不能覆盖清晰度或画幅。");
        }
        if (request.mode() == AiConversationVideoMode.VIDEO_EDIT
                && request.durationSeconds() != null) {
            throw invalidVideoRequest("视频编辑不能传递生成秒数。");
        }
        if (request.mode() == AiConversationVideoMode.VIDEO_EXTEND
                && request.durationSeconds() == null) {
            throw invalidVideoRequest("视频延长必须指定新增秒数。");
        }
    }

    private static AiConversationAttachment requiredMp4Input(
            List<AiConversationAttachment> selected) {
        if (selected.size() != 1) {
            throw invalidVideoRequest("视频编辑和延长必须选择一个 MP4 视频。");
        }
        AiConversationAttachment attachment = selected.getFirst();
        if (attachment.category() != AiConversationAttachmentCategory.VIDEO
                || !"video/mp4".equalsIgnoreCase(attachment.contentType())) {
            throw invalidVideoRequest("视频编辑和延长只支持 MP4 输入。");
        }
        return attachment;
    }

    private static AiConversationVideoResolution inheritedResolution(
            AiConversationVideoInputMetadata metadata) {
        int shortEdge = Math.min(metadata.width(), metadata.height());
        if (shortEdge <= 480) {
            return AiConversationVideoResolution.P480;
        }
        if (shortEdge <= 720) {
            return AiConversationVideoResolution.P720;
        }
        throw invalidVideoRequest("视频编辑和延长的输入清晰度最高为 720p。");
    }

    private static void validateEditAttachments(
            AiModelProvider provider,
            List<AiConversationAttachment> attachments) {
        if ((provider == AiModelProvider.XAI
                || provider == AiModelProvider.GOOGLE)
                && attachments.size() > 3) {
            throw invalidImageRequest("当前供应商的图片编辑最多支持三张参考图。");
        }
        for (AiConversationAttachment attachment : attachments) {
            if (attachment.category() != AiConversationAttachmentCategory.IMAGE
                    || !SUPPORTED_EDIT_IMAGE_TYPES.contains(
                            attachment.contentType().toLowerCase(java.util.Locale.ROOT))) {
                throw invalidImageRequest(
                        "图片编辑只支持 PNG、JPEG 和 WebP 输入图片。");
            }
        }
    }

    private static AiConversationReservationMetering reservationMeteringWithoutVideo(
            AiConversationStreamingStrategy strategy,
            AiModelCacheEntry model,
            long estimatedPromptTokens,
            AiConversationImageGenerationOptions imageGeneration) {
        if (strategy.meteringBasis()
                == AiConversationMeteringBasis.PROVIDER_COST_TICKS) {
            if (imageGeneration == null) {
                throw new IllegalStateException(
                        "Provider-cost strategy requires image generation options.");
            }
            return new ProviderCostReservationMetering(
                    imageGeneration.outputCount());
        }
        return new TokenReservationMetering(
                estimatedPromptTokens,
                model.maxOutputTokens(),
                model.inputRatio(),
                model.cachedInputRatio(),
                model.outputRatio());
    }

    private AiConversationReservationMetering reservationMetering(
            AiConversationStreamingStrategy strategy,
            AiModelCacheEntry model,
            long estimatedPromptTokens,
            AiConversationImageGenerationOptions imageGeneration,
            AiConversationVideoGenerationOptions videoGeneration) {
        if (videoGeneration == null) {
            return reservationMeteringWithoutVideo(
                    strategy,
                    model,
                    estimatedPromptTokens,
                    imageGeneration);
        }
        if (strategy.meteringBasis()
                != AiConversationMeteringBasis.PROVIDER_COST_TICKS) {
            throw new IllegalStateException(
                    "Video generation must use provider-cost ticks metering.");
        }
        AiConversationVideoModelProfile profile = videoProfileService.required(
                AiModelProvider.fromVendor(model.vendor()),
                model.modelName(),
                videoGeneration.mode(),
                videoGeneration.resolution());
        long estimatedTicks = videoCostEstimator.estimateCostTicks(
                videoGeneration, profile.pricing());
        return new VideoProviderCostReservationMetering(
                videoGeneration.mode(),
                videoGeneration.resolution(),
                videoGeneration.durationSeconds(),
                videoGeneration.inputImageCount(),
                videoGeneration.inputVideoDurationMillis(),
                profile.pricing().requiredOutputCostTicksPerSecond(
                        videoGeneration.resolution()),
                profile.pricing().imageInputCostTicksEach(),
                profile.pricing().videoInputCostTicksPerSecond(),
                estimatedTicks);
    }

    private static AiConversationException invalidImageRequest(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                message,
                false);
    }

    private static AiConversationException invalidVideoRequest(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                message == null || message.isBlank()
                        ? "视频请求参数无效。"
                        : message,
                false);
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
