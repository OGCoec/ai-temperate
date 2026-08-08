package com.example.temperate.service.user.aiconversation.generation.worker.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadResult;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadProgressAwareSession;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedUploadSession;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyPermit;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyService;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionCoordinator;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationEphemeralStart;
import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCancelSource;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalResult;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgressPublisher;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.generation.input.AiConversationGenerationInputCodec;
import com.example.temperate.service.user.aiconversation.generation.input.AiConversationGenerationInputSnapshot;
import com.example.temperate.service.user.aiconversation.generation.billing.AiConversationGenerationBillingTransactionService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationActiveRegistry;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationClaim;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationControlService;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorkItem;
import com.example.temperate.service.user.aiconversation.generation.worker.AiConversationGenerationWorker;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLease;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseService;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseType;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelClient;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteredUsage;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiConversationProviderCostUsage;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImageFormat;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewProcessor;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewPublishResult;
import com.example.temperate.service.user.aiconversation.image.AiConversationPreparedImagePreview;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageMeteringEvidence;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageMeteringStatus;
import com.example.temperate.service.user.aiconversation.image.AiConversationPersistedGeneratedAttachmentCodec;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingDiagnosticContext;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategyRegistry;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamBatcher;
import com.example.temperate.service.user.aiconversation.video.AiConversationGeneratedVideo;
import com.example.temperate.service.user.aiconversation.video.AiConversationPersistedVideoResult;
import com.example.temperate.service.user.aiconversation.video.AiConversationPersistedVideoResultCodec;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationOptions;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoGenerationStage;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMeteringEvidence;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoObjectKeyFactory;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferCommand;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferResult;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.SynchronousSink;

/**
 * 在 Rabbit Worker 生命周期内持有模型流、最终图片 OSS 持久化和完整租约，并依据各图片槽位结果冻结唯一终态。
 *
 * <p>SSE Observer 从不持有本订阅；图片子流允许局部失败并保留成功槽位，只有整批无可用结果或系统边界失败才进入失败终态。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
public final class AiConversationGenerationWorkerImpl
        implements AiConversationGenerationWorker {

    private static final int MAX_ASSISTANT_BYTES = 4 * 1024 * 1024;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private final AiConversationGenerationControlService controlService;
    private final AiConversationGenerationActiveRegistry activeRegistry;
    private final AiConversationGenerationTerminalService terminalService;
    private final AiModelCacheService modelCacheService;
    private final AiConversationContextService contextService;
    private final AiConversationContextStore contextStore;
    private final AiConversationConcurrencyService concurrencyService;
    private final AiConversationLeaseService leaseService;
    private final AiConversationModelClient modelClient;
    private final AiConversationGenerationOutputStore outputStore;
    private final AiConversationMediaUploadProgressPublisher mediaUploadProgressPublisher;
    private final AiConversationProperties conversationProperties;
    private final AiConversationAsyncGenerationProperties asyncProperties;
    private final HybridBase64UrlCodec idCodec;
    private final PublicIdCodec publicIdCodec;
    private final AiConversationStreamTimingDiagnosticService timingDiagnosticService;
    private final AiConversationStreamTransportDiagnosticService transportDiagnosticService;
    private final AiConversationStreamTimingClock timingClock;
    private final ObjectMapper objectMapper;
    private final AiConversationGenerationInputCodec inputCodec;
    private final AiConversationMetrics metrics;
    private final AiConversationStreamingStrategyRegistry streamingStrategyRegistry;
    private final AiConversationAttachmentService attachmentService;
    private final AiConversationGenerationBillingTransactionService billingTransactionService;
    private final AiConversationImagePreviewProcessor imagePreviewProcessor;
    private final AiConversationImagePreviewBroker imagePreviewBroker;
    private final AiConversationPersistedGeneratedAttachmentCodec generatedAttachmentCodec;
    private final AiConversationVideoTransferService videoTransferService;
    private final AiConversationVideoObjectKeyFactory videoObjectKeyFactory;
    private final AiConversationVideoGenerationProperties videoProperties;
    private final AiConversationPersistedVideoResultCodec persistedVideoResultCodec;
    private final AiConversationCompactionCoordinator compactionCoordinator;
    private final long maximumGeneratedImageBatchBytes;

    public AiConversationGenerationWorkerImpl(
            AiConversationGenerationControlService controlService,
            AiConversationGenerationActiveRegistry activeRegistry,
            AiConversationGenerationTerminalService terminalService,
            AiModelCacheService modelCacheService,
            AiConversationContextService contextService,
            AiConversationContextStore contextStore,
            AiConversationConcurrencyService concurrencyService,
            AiConversationLeaseService leaseService,
            AiConversationModelClient modelClient,
            AiConversationGenerationOutputStore outputStore,
            AiConversationProperties conversationProperties,
            AiConversationAsyncGenerationProperties asyncProperties,
            HybridBase64UrlCodec idCodec,
            PublicIdCodec publicIdCodec,
            AiConversationStreamTimingDiagnosticService timingDiagnosticService,
            AiConversationStreamTimingClock timingClock,
            AiConversationMetrics metrics,
            ObjectMapper objectMapper,
            AiConversationCompactionCoordinator compactionCoordinator) {
        this(
                controlService,
                activeRegistry,
                terminalService,
                modelCacheService,
                contextService,
                contextStore,
                concurrencyService,
                leaseService,
                modelClient,
                outputStore,
                conversationProperties,
                asyncProperties,
                idCodec,
                publicIdCodec,
                timingDiagnosticService,
                timingClock,
                metrics,
                objectMapper,
                AiConversationStreamTransportDiagnosticService.noOp(),
                null,
                null,
                null,
                AiConversationImagePreviewProcessor.noOp(),
                AiConversationImagePreviewBroker.noOp(),
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper),
                null,
                null,
                null,
                AiConversationVideoGenerationProperties.officialDefaults(),
                new AiConversationPersistedVideoResultCodec(objectMapper),
                AiConversationMediaUploadProgressPublisher.noOp(),
                compactionCoordinator);
    }

    public AiConversationGenerationWorkerImpl(
            AiConversationGenerationControlService controlService,
            AiConversationGenerationActiveRegistry activeRegistry,
            AiConversationGenerationTerminalService terminalService,
            AiModelCacheService modelCacheService,
            AiConversationContextService contextService,
            AiConversationContextStore contextStore,
            AiConversationConcurrencyService concurrencyService,
            AiConversationLeaseService leaseService,
            AiConversationModelClient modelClient,
            AiConversationGenerationOutputStore outputStore,
            AiConversationProperties conversationProperties,
            AiConversationAsyncGenerationProperties asyncProperties,
            HybridBase64UrlCodec idCodec,
            PublicIdCodec publicIdCodec,
            AiConversationStreamTimingDiagnosticService timingDiagnosticService,
            AiConversationStreamTimingClock timingClock,
            AiConversationMetrics metrics,
            ObjectMapper objectMapper,
            AiConversationStreamTransportDiagnosticService transportDiagnosticService,
            AiConversationCompactionCoordinator compactionCoordinator) {
        this(
                controlService,
                activeRegistry,
                terminalService,
                modelCacheService,
                contextService,
                contextStore,
                concurrencyService,
                leaseService,
                modelClient,
                outputStore,
                conversationProperties,
                asyncProperties,
                idCodec,
                publicIdCodec,
                timingDiagnosticService,
                timingClock,
                metrics,
                objectMapper,
                transportDiagnosticService,
                null,
                null,
                null,
                AiConversationImagePreviewProcessor.noOp(),
                AiConversationImagePreviewBroker.noOp(),
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper),
                null,
                null,
                null,
                AiConversationVideoGenerationProperties.officialDefaults(),
                new AiConversationPersistedVideoResultCodec(objectMapper),
                AiConversationMediaUploadProgressPublisher.noOp(),
                compactionCoordinator);
    }

    @Autowired
    public AiConversationGenerationWorkerImpl(
            AiConversationGenerationControlService controlService,
            AiConversationGenerationActiveRegistry activeRegistry,
            AiConversationGenerationTerminalService terminalService,
            AiModelCacheService modelCacheService,
            AiConversationContextService contextService,
            AiConversationContextStore contextStore,
            AiConversationConcurrencyService concurrencyService,
            AiConversationLeaseService leaseService,
            AiConversationModelClient modelClient,
            AiConversationGenerationOutputStore outputStore,
            AiConversationProperties conversationProperties,
            AiConversationAsyncGenerationProperties asyncProperties,
            HybridBase64UrlCodec idCodec,
            PublicIdCodec publicIdCodec,
            AiConversationStreamTimingDiagnosticService timingDiagnosticService,
            AiConversationStreamTimingClock timingClock,
            AiConversationMetrics metrics,
            ObjectMapper objectMapper,
            AiConversationStreamTransportDiagnosticService transportDiagnosticService,
            AiConversationStreamingStrategyRegistry streamingStrategyRegistry,
            AiConversationAttachmentService attachmentService,
            AiConversationGenerationBillingTransactionService billingTransactionService,
            AiConversationImagePreviewProcessor imagePreviewProcessor,
            AiConversationImagePreviewBroker imagePreviewBroker,
            AiConversationPersistedGeneratedAttachmentCodec generatedAttachmentCodec,
            AiConversationAttachmentProperties attachmentProperties,
            AiConversationVideoTransferService videoTransferService,
            AiConversationVideoObjectKeyFactory videoObjectKeyFactory,
            AiConversationVideoGenerationProperties videoProperties,
            AiConversationPersistedVideoResultCodec persistedVideoResultCodec,
            AiConversationMediaUploadProgressPublisher mediaUploadProgressPublisher,
            AiConversationCompactionCoordinator compactionCoordinator) {
        this.controlService = Objects.requireNonNull(controlService);
        this.activeRegistry = Objects.requireNonNull(activeRegistry);
        this.terminalService = Objects.requireNonNull(terminalService);
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.contextService = Objects.requireNonNull(contextService);
        this.contextStore = Objects.requireNonNull(contextStore);
        this.concurrencyService = Objects.requireNonNull(concurrencyService);
        this.leaseService = Objects.requireNonNull(leaseService);
        this.modelClient = Objects.requireNonNull(modelClient);
        this.outputStore = Objects.requireNonNull(outputStore);
        this.mediaUploadProgressPublisher = Objects.requireNonNull(mediaUploadProgressPublisher);
        this.conversationProperties = Objects.requireNonNull(conversationProperties);
        this.asyncProperties = Objects.requireNonNull(asyncProperties);
        this.idCodec = Objects.requireNonNull(idCodec);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.timingDiagnosticService = Objects.requireNonNull(timingDiagnosticService);
        this.transportDiagnosticService = Objects.requireNonNull(transportDiagnosticService);
        this.timingClock = Objects.requireNonNull(timingClock);
        this.metrics = Objects.requireNonNull(metrics);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.inputCodec = new AiConversationGenerationInputCodec(this.objectMapper);
        this.streamingStrategyRegistry = streamingStrategyRegistry;
        this.attachmentService = attachmentService;
        this.billingTransactionService = billingTransactionService;
        this.imagePreviewProcessor = Objects.requireNonNull(imagePreviewProcessor);
        this.imagePreviewBroker = Objects.requireNonNull(imagePreviewBroker);
        this.generatedAttachmentCodec = Objects.requireNonNull(generatedAttachmentCodec);
        this.videoTransferService = videoTransferService;
        this.videoObjectKeyFactory = videoObjectKeyFactory;
        this.videoProperties = Objects.requireNonNull(videoProperties);
        this.persistedVideoResultCodec = Objects.requireNonNull(persistedVideoResultCodec);
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator);
        this.maximumGeneratedImageBatchBytes = attachmentProperties == null
                ? Long.MAX_VALUE
                : attachmentProperties.maxTotalBytesPerMessage();
    }

    @Override
    public void execute(String generationPublicId, String traceId) {
        byte[] generationId = idCodec.decode(generationPublicId);
        AiConversationGenerationClaim claim = controlService.claim(generationId);
        if ("MISSING".equals(claim.outcome()) || "ALREADY_TERMINAL".equals(claim.outcome())) {
            return;
        }
        if ("CANCELLED_BEFORE_START".equals(claim.outcome())) {
            activeRegistry.clear(generationPublicId);
            preserveUserStopBeforeStart(claim.workItem());
            freezeCancellation(claim.workItem(), "", null, traceId);
            return;
        }
        if ("ALREADY_RUNNING".equals(claim.outcome())) {
            // 重复投递不能启动第二个上游调用；真正失去 Owner 的任务由一分钟恢复兜底在最大时限后冻结。
            return;
        }
        metrics.generationStarted();
        runClaimed(generationPublicId, claim.workItem(), traceId);
    }

    private void runClaimed(
            String generationPublicId,
            AiConversationGenerationWorkItem workItem,
            String traceId) {
        AiConversationGeneration generation = workItem.generation();
        AiConversationGenerationPayload payload = workItem.payload();
        String conversationPublicId = idCodec.encode(generation.getConversationId());
        AiConversationConcurrencyPermit permit = null;
        AiConversationLease lease = null;
        Disposable subscription = null;
        Disposable.Composite lifecycle = null;
		long workerDeadlineNanos = deadlineAfter(asyncProperties.maxWorkerDuration());
		WorkerState state = new WorkerState(maximumGeneratedImageBatchBytes);
		state.generationId = generation.getId();
        try {
            if (payload.getUpstreamRequestId() != null) {
                // 已冻结 ID 非法时宁可停止并对账，也不能把损坏数据当作“尚未创建”而重复 POST。
                state.upstreamRequestId.set(requiredUpstreamRequestId(
                        payload.getUpstreamRequestId()));
            }
            AiModelCacheEntry model = requiredModel(generation.getModelId());
            if (workItem.usageDetail() == null) {
                throw new IllegalStateException(
                        "AI Generation frozen vendor snapshot is missing.");
            }
            AiModelProvider provider = AiModelProvider.fromVendor(
                    workItem.usageDetail().getVendorSnapshot());
            AiConversationReasoningEffort reasoningEffort =
                    AiConversationReasoningEffort.fromLevel(
                            payload.getReasoningEffort().shortValue());
            provider.validateReasoningEffort(reasoningEffort);
            AiConversationGenerationInputSnapshot inputSnapshot =
                    inputCodec.decode(payload.getInputAttachmentsJson());
            AiConversationImageGenerationOptions imageGeneration =
                    inputSnapshot.imageGeneration();
			AiConversationVideoGenerationOptions videoGeneration =
					inputSnapshot.videoGeneration();
			if (videoGeneration != null && state.upstreamRequestId.get() == null) {
				controlService.updateVideoStage(
						generation.getId(), AiConversationVideoGenerationStage.VALIDATING_MEDIA);
				controlService.updateVideoStage(
						generation.getId(), AiConversationVideoGenerationStage.RESERVED);
			}
            AiConversationContent input = new AiConversationContent(
                    payload.getInputText(), inputSnapshot.attachments());
            AiConversationPromptSnapshot prompt = contextService.prepare(
                    generation.getConversationId(), conversationPublicId, model, input);
            short concurrencyWeight = imageGeneration == null
                    ? (short) 1
                    : imageGeneration.outputCount();
            permit = concurrencyService.tryAcquire(
                            generation.getLoginIdentityId(), concurrencyWeight)
                    .orElseThrow(() -> new AiConversationException(
                            AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                            "图片或模型生成服务当前繁忙，请稍后重试。",
                            true));
            lease = leaseService.tryAcquire(
                            conversationPublicId, AiConversationLeaseType.INFLIGHT)
                    .orElseThrow(() -> new IllegalStateException(
                            "AI Generation conversation lease is unavailable."));
            lifecycle = Disposables.composite();
            activeRegistry.register(generationPublicId, lifecycle);
            startLifecycleRenewal(lifecycle, lease, permit, state);
            AiConversationEphemeralStart ephemeral = contextStore.appendEphemeralUser(
                    conversationPublicId,
                    prompt.generation(),
                    idCodec.encode(generation.getUsageId()),
                    input);
            if (ephemeral.outcome() != AiConversationContextWriteOutcome.APPLIED) {
                throw new IllegalStateException("AI Generation context snapshot is unavailable.");
            }
            state.cacheGeneration = prompt.generation();
            state.ephemeralOrdinal = ephemeral.ordinal();
            // Billing 在独立消费者中提交成功后需要凭此游标把 Redis 临时轮次升级为持久轮次。
            controlService.bindContextCursor(
                    generation.getId(), state.cacheGeneration, state.ephemeralOrdinal);
            payload.setContextGeneration(state.cacheGeneration);
            payload.setEphemeralOrdinal(state.ephemeralOrdinal);

            // 取消可能发生在 Worker 抢占任务后、真正建立上游订阅前；先读权威状态，
            // 避免已经明确取消的任务仍额外发起一次模型调用。
            AiConversationGenerationWorkItem beforeUpstream = controlService.load(generation.getId());
            if (beforeUpstream != null
                    && beforeUpstream.generation().getGenerationStatus()
                            == AiConversationGenerationStatus.CANCEL_REQUESTED.code()) {
                markInterrupted(beforeUpstream, conversationPublicId, state);
                freezeCancellation(
                        beforeUpstream,
                        state.answer.toString(),
                        state.usage.get(),
                        state,
                        traceId);
                return;
            }

            if (imageGeneration != null || videoGeneration != null) {
                AiConversationGenerationBillingTransactionService billing =
                        requiredImageDependency(billingTransactionService);
                // 正式 Object Key 依赖稳定消息 ID；在上游订阅前只预留 ID，不提前创建消息或冻结计费终态。
                long messageId = billing.getOrReserveMessageId(generation.getId());
                state.reservedMessageId = messageId;
                if (imageGeneration != null) {
                    AiConversationAttachmentService attachments =
                            requiredImageDependency(attachmentService);
                    state.imageUploadSession = attachments.openGeneratedUploadSession(
                            publicIdCodec.encode(generation.getLoginIdentityId()),
                            conversationPublicId,
                            publicIdCodec.encode(messageId));
                    if (state.imageUploadSession
                            instanceof AiConversationGeneratedUploadProgressAwareSession session) {
                        session.setProgressListener(progress -> mediaUploadProgressPublisher
                                .publish(generationPublicId, progress));
                    }
                }
            }

            // Worker 与 SSE Observer 是被 Redis 分隔的两次订阅；此处必须在最终订阅外层建立 Context，
            // 才能让模型客户端的 AOP 在向上游订阅时读取同一个时序会话。
            AiConversationStreamTimingContext timingContext =
                    new AiConversationStreamTimingContext(
                            traceId,
                            idCodec.encode(generation.getUsageId()),
                            conversationPublicId,
                            publicIdCodec.encode(generation.getModelId()),
                            AiConversationStreamTimingPath.ASYNC_GENERATION_WORKER,
                            timingClock.nanoTime());
            Flux<AiConversationModelEvent> modelEvents;
            if (videoGeneration != null) {
                modelEvents = videoModelEvents(
                        model,
                        payload,
                        prompt,
                        inputSnapshot.attachments(),
                        videoGeneration,
                        provider,
                        state.upstreamRequestId.get());
            } else if (imageGeneration == null) {
                AiConversationModelRequest modelRequest =
                        new AiConversationModelRequest(
                                provider,
                                model.modelName(),
                                model.maxOutputTokens(),
                                reasoningEffort,
                                prompt);
                AiConversationStreamingProtocol protocol =
                        inputSnapshot.webSearchMode()
                                        == com.example.temperate.service.user.aiconversation
                                                .response.AiConversationWebSearchMode.OFF
                                ? AiConversationStreamingProtocol.CHAT_COMPLETIONS
                                : AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH;
                if (streamingStrategyRegistry == null) {
                    // 仅保留给旧单元测试构造器；Spring 生产构造器始终注入完整策略注册表。
                    modelEvents = modelClient.stream(modelRequest)
                            .map(AiConversationModelEvent.Chunk::new);
                } else {
                    var strategy = streamingStrategyRegistry.getRequired(
                            provider, protocol);
                    requireMeteringBasis(payload, strategy.meteringBasis());
                    modelEvents = strategy.stream(new AiConversationStreamingRequest(
                            modelRequest, inputSnapshot.webSearchMode()));
                }
            } else {
                modelEvents = imageModelEvents(
                        model,
                        payload,
                        prompt,
                        inputSnapshot.attachments(),
                        imageGeneration,
                        provider,
                        timingContext,
                        generationPublicId);
            }
            Flux<AiConversationModelChunk> upstream = modelEvents.handle(
                    (event, sink) -> acceptModelEvent(
                            generationPublicId,
                            timingContext,
                            state,
                            event,
                            sink));
            AtomicInteger redisChunk = new AtomicInteger();
            Flux<AiConversationModelChunk> batched = AiConversationStreamBatcher.forwardWhileBatching(
                    upstream,
                    conversationProperties.streamFlushBytes(),
                    conversationProperties.streamFlushInterval(),
                    (modelChunkBatch, flushReason) -> {
                        int deltaBytes = modelChunkBatch.stream()
                                .mapToInt(chunk -> chunk.text() == null
                                        ? 0
                                        : chunk.text().getBytes(StandardCharsets.UTF_8).length)
                                .sum();
                        int deltaChars = modelChunkBatch.stream()
                                .mapToInt(chunk -> chunk.text() == null
                                        ? 0 : chunk.text().length())
                                .sum();
                        transportDiagnosticService.recordSafely(
                                timingContext,
                                "ai_stream_worker_flush",
                                Map.of(
                                        "generationPublicId", generationPublicId,
                                        "flushReason", flushReason,
                                        "chunkCount", modelChunkBatch.size(),
                                        "deltaBytes", deltaBytes,
                                        "deltaChars", deltaChars));
                        List<String> chunks = modelChunkBatch.stream()
                                .map(chunk -> Objects.requireNonNullElse(
                                        Objects.requireNonNull(
                                                        chunk,
                                                        "AI model chunk must not be null")
                                                .text(),
                                        ""))
                                .filter(text -> !text.isEmpty())
                                .toList();
                        if (chunks.isEmpty()) {
                            return;
                        }
                        if (contextStore.appendAssistantChunks(
                                conversationPublicId,
                                state.cacheGeneration,
                                state.ephemeralOrdinal,
                                redisChunk.getAndAdd(chunks.size()),
                                chunks) != AiConversationContextWriteOutcome.APPLIED) {
                            throw new IllegalStateException("AI Generation context append failed.");
                        }
                        outputStore.appendDelta(
                                generationPublicId,
                                String.join("", chunks),
                                timingContext,
                                Map.of(
                                        "generationPublicId", generationPublicId,
                                        "flushReason", flushReason,
                                        "chunkCount", modelChunkBatch.size(),
                                        "deltaBytes", deltaBytes,
                                        "deltaChars", deltaChars));
                    });
            Flux<AiConversationModelChunk> observedBatcher =
                    timingDiagnosticService.observeBoundary(
                            batched,
                            AiConversationStreamTimingBoundary.AFTER_STREAM_BATCHER,
                            chunk -> chunk.text() == null ? 0 : chunk.text().length());
			Flux<AiConversationModelChunk> timed = timingDiagnosticService.withSession(
					observedBatcher, timingContext);
			if (videoGeneration != null) {
				AiConversationVideoGenerationStage stage = state.upstreamRequestId.get() == null
						? AiConversationVideoGenerationStage.XAI_SUBMITTING
						: AiConversationVideoGenerationStage.XAI_PENDING;
				controlService.updateVideoStage(generation.getId(), stage);
				state.videoPendingStage.set(
						stage == AiConversationVideoGenerationStage.XAI_PENDING);
			}
			subscription = timed
                    .doFinally(signal -> {
                        state.signal.set(signal);
                        state.finished.countDown();
                    })
                    .subscribe(
                            chunk -> acceptChunk(state, chunk),
                            state.failure::set);
            lifecycle.add(subscription);
            long upstreamRemaining = remainingNanos(workerDeadlineNanos);
            boolean finished = upstreamRemaining > 0L
                    && state.finished.await(upstreamRemaining, TimeUnit.NANOSECONDS);
            if (!finished) {
                lifecycle.dispose();
                freezeFailureOrCancellation(
                        workItem,
                        AiConversationGenerationTerminalType.SYSTEM_FAILED,
                        "AI_GENERATION_WORKER_TIMEOUT",
                        state.answer.toString(),
                        state.usage.get(),
                        state,
                        traceId);
                return;
            }
            AiConversationGenerationWorkItem latest = controlService.load(generation.getId());
            if (imageGeneration != null
                    && isRuntimeLinkageFailure(state.failure.get())) {
                // 实例级故障下所有 FINAL 都只是未持久化内存数据，必须先清除再计算成功数与终态 Usage。
                failUnfinishedImageOutputs(
                        generationPublicId,
                        imageGeneration.outputCount(),
                        state,
                        state.failure.get());
            }
            if (imageGeneration != null) {
                state.usage.set(state.aggregateImageUsage());
                transportDiagnosticService.recordSafely(
                        timingContext,
                        "ai_image_substreams_completed",
                        Map.of(
                                "generationPublicId", generationPublicId,
                                "requestedCount", imageGeneration.outputCount(),
                                "successfulCount", state.finalImages.size(),
                                "subrequests", state.upstreamRequestIds.entrySet().stream()
                                        .map(entry -> Map.of(
                                                "outputIndex", entry.getKey(),
                                                "requestIdPresent", true))
                                        .toList()));
                if (state.finalImages.isEmpty() && !state.imageFailures.isEmpty()) {
                    state.failure.compareAndSet(
                            null, state.imageFailures.values().iterator().next());
                }
            }
            if (latest != null
                    && latest.generation().getGenerationStatus()
                    == AiConversationGenerationStatus.CANCEL_REQUESTED.code()) {
                markInterrupted(latest, conversationPublicId, state);
                freezeCancellation(
                        latest,
                        state.answer.toString(),
                        state.usage.get(),
                        state,
                        traceId);
            } else if (state.failure.get() != null) {
                markInterrupted(workItem, conversationPublicId, state);
                freezeFailure(
                        workItem,
                        upstreamFailure(state.failure.get()),
                        failureCode(state.failure.get()),
                        state.answer.toString(),
                        state.usage.get(),
                        state,
                        traceId);
            } else if (state.signal.get() == SignalType.ON_COMPLETE
                    && (imageGeneration != null
                            ? !state.finalImages.isEmpty()
                            : videoGeneration != null
                                    ? state.generatedVideo.get() != null
                                            && state.videoMeteringEvidence.get() != null
                                    : state.usage.get() != null)) {
                if (videoGeneration != null) {
                    freezeCompletedVideo(
                            generationPublicId,
                            generation,
                            conversationPublicId,
                            state,
                            traceId);
                } else if (imageGeneration == null) {
                    terminalService.freeze(new AiConversationGenerationTerminalCommand(
                            generation.getId(),
                            AiConversationGenerationTerminalType.COMPLETED,
                            Objects.requireNonNullElse(state.finishReason.get(), "STOP"),
                            state.answer.toString(),
                            json(state.generatedMedia),
                            state.usage.get(),
                            state.finishReason.get(),
                            state.upstreamRequestId.get(),
                            traceId));
                } else {
                    freezeCompletedImage(
                            generationPublicId,
                            generation,
                            conversationPublicId,
                            state,
                            imageGeneration.outputCount(),
                            workerDeadlineNanos,
                            traceId);
                }
            } else {
                markInterrupted(workItem, conversationPublicId, state);
                freezeFailure(
                        workItem,
                        AiConversationGenerationTerminalType.SYSTEM_FAILED,
                        "AI_STREAM_TERMINATED_WITHOUT_USAGE",
                        state.answer.toString(),
                        null,
                        state,
                        traceId);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            freezeFailureOrCancellation(
                    workItem,
                    AiConversationGenerationTerminalType.SYSTEM_FAILED,
                    "AI_GENERATION_WORKER_INTERRUPTED",
                    state.answer.toString(),
                    state.usage.get(),
                    state,
                    traceId);
        } catch (RuntimeException failure) {
            boolean terminalClaimed = freezeFailureOrCancellation(
                    workItem,
                    upstreamFailure(failure),
                    failureCode(failure),
                    state.answer.toString(),
                    state.usage.get(),
                    state,
                    traceId);
            if (!terminalClaimed) {
                // 终态已经由先前事务冻结时，原异常通常代表 Terminal Confirm 失败，必须阻止 Generation ACK。
                throw failure;
            }
        } finally {
            abortImageUploadsSafely(state);
            try {
                // Billing 可能由其他实例消费；本机 Worker 必须自行安排预览宽限释放，避免依赖远端 release。
                imagePreviewBroker.seal(generationPublicId);
            } catch (RuntimeException ignoredFailure) {
                // maximumLifetime 仍会兜底，本地预览清理失败不得覆盖 PostgreSQL 终态。
            }
            if (lifecycle != null) {
                // 上游、续租和 OSS 最终化共享同一生命周期；终态事务结束后才能停止心跳并释放完整权重。
                lifecycle.dispose();
                activeRegistry.remove(generationPublicId, lifecycle);
            } else {
                activeRegistry.clear(generationPublicId);
            }
            if (lease != null) {
                try {
                    leaseService.release(lease);
                } catch (RuntimeException ignoredFailure) {
                    // 短租约 TTL 会收敛；释放失败不能覆盖已经冻结的终态。
                }
            }
            if (permit != null) {
                try {
                    concurrencyService.release(permit);
                } catch (RuntimeException ignoredFailure) {
                    // 并发令牌 TTL 会收敛；释放失败不能覆盖已经冻结的终态。
                }
            }
        }
    }

    private void startLifecycleRenewal(
            Disposable.Composite lifecycle,
            AiConversationLease lease,
            AiConversationConcurrencyPermit permit,
            WorkerState state) {
        if (lifecycle.isDisposed()) {
            return;
        }
        Disposable heartbeat = Flux.interval(HEARTBEAT_INTERVAL)
                .subscribe(ignored -> {
                    try {
                        if (!leaseService.renew(lease)
                                || !concurrencyService.renew(permit)) {
                            failLifecycleRenewal(lifecycle, state, null);
                        }
                    } catch (RuntimeException failure) {
                        failLifecycleRenewal(lifecycle, state, failure);
                    }
                });
        lifecycle.add(heartbeat);
    }

    private static void failLifecycleRenewal(
            Disposable.Composite lifecycle,
            WorkerState state,
            RuntimeException cause) {
        IllegalStateException failure = new IllegalStateException(
                "AI Generation worker lease renewal failed.", cause);
        if (state.failure.compareAndSet(null, failure)) {
            lifecycle.dispose();
        }
    }

    private Flux<AiConversationModelEvent> videoModelEvents(
            AiModelCacheEntry model,
            AiConversationGenerationPayload payload,
            AiConversationPromptSnapshot prompt,
            List<AiConversationAttachment> attachments,
            AiConversationVideoGenerationOptions videoGeneration,
            AiModelProvider provider,
            String resumeUpstreamRequestId) {
        AiConversationAttachmentService attachmentPort =
                requiredImageDependency(attachmentService);
        Map<String, AiConversationAttachment> byId = attachments.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        AiConversationAttachment::attachmentId,
                        attachment -> attachment));
        List<String> inputUrls = videoGeneration.inputAttachmentPublicIds().stream()
                .map(publicId -> {
                    AiConversationAttachment attachment = byId.get(publicId);
                    if (attachment == null) {
                        throw new IllegalStateException(
                                "Frozen video input attachment is unavailable.");
                    }
                    return attachmentPort.resolveModelUrl(attachment);
                })
                .toList();
        AiConversationModelRequest request = new AiConversationModelRequest(
                provider,
                model.modelName(),
                model.maxOutputTokens(),
                AiConversationReasoningEffort.fromLevel(
                        payload.getReasoningEffort().shortValue()),
                prompt,
                null,
                (short) 0,
                List.of(),
                videoGeneration,
                inputUrls);
        var strategy = Objects.requireNonNull(streamingStrategyRegistry)
                .getRequired(provider, AiConversationStreamingProtocol.VIDEOS_GENERATION);
        requireMeteringBasis(payload, strategy.meteringBasis());
        return strategy.stream(new AiConversationStreamingRequest(
                request,
                com.example.temperate.service.user.aiconversation.response
                        .AiConversationWebSearchMode.OFF,
                null,
                resumeUpstreamRequestId));
    }

    private Flux<AiConversationModelEvent> imageModelEvents(
            AiModelCacheEntry model,
            AiConversationGenerationPayload payload,
            AiConversationPromptSnapshot prompt,
            List<AiConversationAttachment> inputAttachments,
            AiConversationImageGenerationOptions imageGeneration,
            AiModelProvider provider,
            AiConversationStreamTimingContext timingContext,
            String generationPublicId) {
        var strategy = requiredImageDependency(streamingStrategyRegistry)
                .getRequired(provider, AiConversationStreamingProtocol.IMAGES_GENERATION);
        requireMeteringBasis(payload, strategy.meteringBasis());
        List<String> imageInputUrls = imageGeneration.action()
                == AiConversationImageAction.EDIT
                ? inputAttachments.stream()
                        // 签名地址只在 Worker 内存中生成一次并复用于同批子流，禁止进入快照、日志或终态证据。
                        .map(requiredImageDependency(attachmentService)::resolveModelUrl)
                        .toList()
                : List.of();
        short outputCount = imageGeneration.outputCount();
        return Flux.range(0, outputCount)
                .flatMap(outputIndex -> {
                    short childIndex = outputIndex.shortValue();
                    AtomicBoolean finalSeen = new AtomicBoolean();
                    AtomicBoolean meteringSeen = new AtomicBoolean();
                    AiConversationModelRequest childRequest =
                            new AiConversationModelRequest(
                                    provider,
                                    model.modelName(),
                                    model.maxOutputTokens(),
                                    AiConversationReasoningEffort.fromLevel(
                                            payload.getReasoningEffort().shortValue()),
                                    prompt,
                                    imageGeneration,
                                    childIndex,
                                    imageInputUrls);
                    return strategy.stream(new AiConversationStreamingRequest(
                                     childRequest,
                                     com.example.temperate.service.user.aiconversation.response
                                             .AiConversationWebSearchMode.OFF,
                                     new AiConversationStreamingDiagnosticContext(
                                             timingContext,
                                             generationPublicId)))
                            .doOnNext(event -> {
                                if (event instanceof AiConversationModelEvent.Image image
                                        && image.value().phase()
                                                == AiConversationGeneratedImagePhase.FINAL) {
                                    finalSeen.set(true);
                                }
                                if (event instanceof AiConversationModelEvent.ImageUsage
                                        || event instanceof
                                                AiConversationModelEvent.ImageCostEvidence) {
                                    meteringSeen.set(true);
                                }
                            })
                            // 子流必须同时交付最终图片与权威计量事件；成本缺失由专用证据事件进入待对账，而不是丢弃图片。
                            .concatWith(Flux.defer(() -> finalSeen.get() && meteringSeen.get()
                                    ? Flux.<AiConversationModelEvent>just(
                                            new AiConversationModelEvent.ImageOutputReady(
                                                    childIndex))
                                    : Flux.<AiConversationModelEvent>just(
                                            new AiConversationModelEvent.ImageFailure(
                                                    childIndex,
                                                    new IllegalStateException(
                                                            "Image child stream completed without a final image or metering evidence.")))))
                            // 普通上游失败只冻结单槽位；本机 LinkageError 说明整个实例不可信，必须取消同批所有子流并立即退款。
                            .onErrorResume(failure ->
                                    recoverImageChildFailure(childIndex, failure));
                }, outputCount);
    }

    /**
     * 把普通子流错误降为单槽位失败，同时让实例级链接故障穿透 flatMap 以取消全部兄弟订阅。
     */
    static Flux<AiConversationModelEvent> recoverImageChildFailure(
            short outputIndex,
            Throwable failure) {
        return isRuntimeLinkageFailure(failure)
                ? Flux.<AiConversationModelEvent>error(failure)
                : Flux.just(new AiConversationModelEvent.ImageFailure(
                        outputIndex, failure));
    }

    private void failUnfinishedImageOutputs(
            String generationPublicId,
            short outputCount,
            WorkerState state,
            Throwable failure) {
        String reasonCode = failureCode(failure);
        for (short outputIndex = 0; outputIndex < outputCount; outputIndex++) {
            // LinkageError 发生时尚未进入 OSS 最终化，内存中的 FINAL 也不是正式成功附件，所有槽位都必须回到失败状态。
            state.removeFinalImage(outputIndex);
            state.imageFailures.put(outputIndex, failure);
            publishPreviewFailureSafely(
                    state, generationPublicId, outputIndex, reasonCode);
        }
    }

    private void acceptChunk(WorkerState state, AiConversationModelChunk chunk) {
        appendBounded(state, chunk.text());
        if (chunk.usage() != null) {
            state.usage.set(chunk.usage());
        }
        if (chunk.finishReason() != null && !chunk.finishReason().isBlank()) {
            state.finishReason.set(chunk.finishReason());
        }
        if (chunk.upstreamRequestId() != null && !chunk.upstreamRequestId().isBlank()) {
            state.upstreamRequestId.set(chunk.upstreamRequestId());
        }
        state.generatedMedia.addAll(chunk.generatedMedia());
    }

    private void acceptModelEvent(
            String generationPublicId,
            AiConversationStreamTimingContext timingContext,
            WorkerState state,
            AiConversationModelEvent event,
            SynchronousSink<AiConversationModelChunk> sink) {
        if (event instanceof AiConversationModelEvent.Chunk chunk) {
            sink.next(chunk.value());
            return;
        }
        if (event instanceof AiConversationModelEvent.Image imageEvent) {
            AiConversationGeneratedImage image = imageEvent.value();
            if (image.phase() == AiConversationGeneratedImagePhase.FINAL) {
                AiConversationGeneratedImage previous = state.finalImages.putIfAbsent(
                        image.outputIndex(), image);
                if (previous != null) {
                    state.removeFinalImage(image.outputIndex(), previous);
                    IllegalStateException duplicateFailure =
                            new IllegalStateException(
                                    "Image child stream returned duplicate final events.");
                    state.imageFailures.putIfAbsent(
                            image.outputIndex(),
                            duplicateFailure);
                    publishPreviewFailureSafely(
                            state,
                            generationPublicId,
                            image.outputIndex(),
                            failureCode(duplicateFailure));
                    return;
                }
                if (!state.reserveFinalBytes(image)) {
                    state.finalImages.remove(image.outputIndex(), image);
                    IllegalStateException sizeFailure = new IllegalStateException(
                            "Image batch decoded bytes exceed the configured limit.");
                    state.imageFailures.putIfAbsent(image.outputIndex(), sizeFailure);
                    publishPreviewFailureSafely(
                            state,
                            generationPublicId,
                            image.outputIndex(),
                            failureCode(sizeFailure));
                    return;
                }
            }
            state.imagePreviewTasks.put(image.outputIndex(), preparePreviewSafely(
                    state,
                    timingContext,
                    generationPublicId,
                    image));
            metrics.imagePreview(image.phase().name());
            return;
        }
        if (event instanceof AiConversationModelEvent.ImageOutputReady ready) {
            submitImageUpload(
                    state,
                    timingContext,
                    generationPublicId,
                    ready.outputIndex());
            return;
        }
        if (event instanceof AiConversationModelEvent.ImageUsage imageUsage) {
            AiConversationMeteredUsage previous = state.imageUsages.putIfAbsent(
                    imageUsage.outputIndex(), imageUsage.usage());
            if (previous != null) {
                // 重复 Usage 使槽位失败，但第一份权威 Usage 仍代表已经发生的上游消耗，不能删除或重复计入。
                state.removeFinalImage(imageUsage.outputIndex());
                IllegalStateException duplicateFailure =
                        new IllegalStateException(
                                "Image child stream returned duplicate usage events.");
                state.imageFailures.putIfAbsent(
                        imageUsage.outputIndex(),
                        duplicateFailure);
                publishPreviewFailureSafely(
                        state,
                        generationPublicId,
                        imageUsage.outputIndex(),
                        failureCode(duplicateFailure));
                return;
            }
            if (imageUsage.usage() instanceof AiConversationProviderCostUsage costUsage) {
                state.imageMeteringEvidence.putIfAbsent(
                        imageUsage.outputIndex(),
                        new AiConversationImageMeteringEvidence(
                                imageUsage.outputIndex(),
                                AiConversationImageMeteringStatus.COMPLETE,
                                imageUsage.upstreamRequestId(),
                                costUsage.costInUsdTicks()));
            }
            if (imageUsage.upstreamRequestId() != null
                    && !imageUsage.upstreamRequestId().isBlank()) {
                String requestId = boundedUpstreamRequestId(
                        imageUsage.upstreamRequestId());
                state.upstreamRequestIds.putIfAbsent(
                        imageUsage.outputIndex(), requestId);
                state.upstreamRequestId.compareAndSet(
                        null, requestId);
            }
            if (imageUsage.finishReason() != null
                    && !imageUsage.finishReason().isBlank()) {
                state.finishReason.compareAndSet(
                        null, imageUsage.finishReason());
            }
            return;
        }
        if (event instanceof AiConversationModelEvent.ImageCostEvidence costEvidence) {
            AiConversationImageMeteringEvidence evidence = costEvidence.evidence();
            AiConversationImageMeteringEvidence previous =
                    state.imageMeteringEvidence.putIfAbsent(
                            evidence.outputIndex(), evidence);
            if (previous != null) {
                state.removeFinalImage(evidence.outputIndex());
                state.imageFailures.putIfAbsent(
                        evidence.outputIndex(),
                        new IllegalStateException(
                                "Image child stream returned duplicate cost evidence."));
                return;
            }
            if (!"unavailable".equals(evidence.requestId())) {
                state.upstreamRequestIds.putIfAbsent(
                        evidence.outputIndex(), evidence.requestId());
                state.upstreamRequestId.compareAndSet(
                        null, evidence.requestId());
            }
            return;
        }
        if (event instanceof AiConversationModelEvent.ImageFailure imageFailure) {
            state.removeFinalImage(imageFailure.outputIndex());
            // 槽位失败只影响结果保留；上游已经报告的 Usage 仍需汇总，未报告时则保持缺省且禁止猜测。
            state.imageFailures.putIfAbsent(
                    imageFailure.outputIndex(), imageFailure.cause());
            publishPreviewFailureSafely(
                    state,
                    generationPublicId,
                    imageFailure.outputIndex(),
                    failureCode(imageFailure.cause()));
            return;
        }
		if (event instanceof AiConversationModelEvent.VideoRequestAccepted accepted) {
			String requestId = requiredUpstreamRequestId(accepted.requestId());
			// 在订阅下一个轮询事件前持久化 request_id，Worker 宕机后只能恢复 GET，禁止再次 POST。
			controlService.bindUpstreamRequestId(state.generationId, requestId);
			String previous = state.upstreamRequestId.getAndSet(requestId);
			if (previous != null && !previous.equals(requestId)) {
				throw new IllegalStateException(
						"AI video request ID conflicts with the active request ID.");
			}
			if (state.videoPendingStage.compareAndSet(false, true)) {
				controlService.updateVideoStage(
						state.generationId,
						AiConversationVideoGenerationStage.XAI_PENDING);
			}
			return;
		}
		if (event instanceof AiConversationModelEvent.VideoProgress progress) {
			if (state.videoPendingStage.compareAndSet(false, true)) {
				controlService.updateVideoStage(
						state.generationId,
						AiConversationVideoGenerationStage.XAI_PENDING);
			}
            outputStore.publishEvent(
                    generationPublicId,
                    "video_generation_progress",
                    json(Map.of("progress", progress.progress())));
            return;
        }
        if (event instanceof AiConversationModelEvent.VideoCostEvidence costEvent) {
            AiConversationVideoMeteringEvidence evidence = costEvent.evidence();
            if (!state.videoMeteringEvidence.compareAndSet(null, evidence)) {
                sink.error(new IllegalStateException(
                        "Video stream returned duplicate cost evidence."));
                return;
            }
            state.upstreamRequestId.compareAndSet(
                    null, boundedUpstreamRequestId(evidence.requestId()));
            if (evidence.costInUsdTicks() != null) {
                state.usage.set(new AiConversationProviderCostUsage(
                        evidence.costInUsdTicks()));
            }
            return;
        }
		if (event instanceof AiConversationModelEvent.Video videoEvent) {
            AiConversationGeneratedVideo video = videoEvent.value();
            if (!state.generatedVideo.compareAndSet(null, video)) {
                sink.error(new IllegalStateException(
                        "Video stream returned duplicate completed results."));
                return;
            }
            state.upstreamRequestId.compareAndSet(
                    null, boundedUpstreamRequestId(video.requestId()));
			state.finishReason.compareAndSet(null, "STOP");
			controlService.updateVideoStage(
					state.generationId, AiConversationVideoGenerationStage.XAI_DONE);
			return;
        }
        if (event instanceof AiConversationModelEvent.Failure failure) {
            sink.error(new IllegalStateException(failure.reasonCode()));
        }
    }

    private void submitImageUpload(
            WorkerState state,
            AiConversationStreamTimingContext timingContext,
            String generationPublicId,
            short outputIndex) {
        if (state.imageFailures.containsKey(outputIndex)) {
            return;
        }
        AiConversationGeneratedImage image = state.finalImages.get(outputIndex);
        if (image == null) {
            throw new IllegalStateException(
                    "Image output became ready without a retained final image.");
        }
        AiConversationGeneratedUploadSession uploadSession = Objects.requireNonNull(
                state.imageUploadSession,
                "Image upload session is unavailable");
        String extension = AiConversationGeneratedImageFormat
                .fromContentType(image.contentType())
                .extension();
        AiConversationGeneratedMedia media = new AiConversationGeneratedMedia(
                "generated-" + (outputIndex + 1) + "." + extension,
                image.contentType(),
                image.bytes());
        long startedNanos = System.nanoTime();
        CompletableFuture<AiConversationGeneratedUploadResult> task = uploadSession
                .submit(outputIndex, media)
                .whenComplete((result, failure) -> {
                    Duration uploadDuration = Duration.ofNanos(
                            System.nanoTime() - startedNanos);
                    if (failure != null) {
                        state.failure.compareAndSet(
                                null,
                                new IllegalStateException(
                                        "Generated image upload task failed.",
                                        failure));
                        publishPreviewFailureSafely(
                                state,
                                generationPublicId,
                                outputIndex,
                                AiConversationAttachment.STORAGE_FAILURE_CODE);
                        metrics.imagePersistence(uploadDuration, "failed");
                        metrics.mediaUpload(uploadDuration, "image", "failed");
                        recordImagePersistenceDiagnostic(
                                timingContext,
                                outputIndex,
                                uploadDuration,
                                "failed");
                        return;
                    }
                    if (result.successful()) {
                        state.persistedImages.put(outputIndex, result);
                        // OSS 已成为该槽位的最新事实；晚到的压缩任务不得把 Broker 和页面降级回临时预览。
                        state.invalidatePreview(outputIndex);
                        publishPersistedSafely(
                                generationPublicId,
                                outputIndex,
                                result.attachment());
                        metrics.imagePersistence(uploadDuration, "success");
                        metrics.mediaUpload(uploadDuration, "image", "success");
                    } else {
                        state.removeFinalImage(outputIndex);
                        state.imageUsages.remove(outputIndex);
                        state.imageMeteringEvidence.remove(outputIndex);
                        state.imageFailures.putIfAbsent(
                                outputIndex,
                                new IllegalStateException(
                                        AiConversationAttachment.STORAGE_FAILURE_CODE));
                        publishPreviewFailureSafely(
                                state,
                                generationPublicId,
                                outputIndex,
                                AiConversationAttachment.STORAGE_FAILURE_CODE);
                        metrics.imagePersistence(uploadDuration, "dropped");
                        metrics.mediaUpload(uploadDuration, "image", "failed");
                    }
                    recordImagePersistenceDiagnostic(
                            timingContext,
                            outputIndex,
                            uploadDuration,
                            result.successful() ? "success" : "dropped");
                });
        if (state.imageUploadTasks.putIfAbsent(outputIndex, task) != null) {
            throw new IllegalStateException(
                    "Image output attempted to submit a duplicate upload task.");
        }
    }

    private void recordImagePersistenceDiagnostic(
            AiConversationStreamTimingContext timingContext,
            short outputIndex,
            Duration duration,
            String outcome) {
        transportDiagnosticService.recordSafely(
                timingContext,
                "ai_image_oss_persistence",
                Map.of(
                        "outputIndex", outputIndex,
                        "phase", "OSS_PERSISTENCE",
                        "durationMillis", duration.toMillis(),
                        "outcome", outcome));
    }

    private void publishPersistedSafely(
            String generationPublicId,
            short outputIndex,
            AiConversationAttachment attachment) {
        try {
            imagePreviewBroker.publishPersisted(
                    generationPublicId, outputIndex, attachment);
        } catch (RuntimeException ignored) {
            // 正式附件仍会进入 completed；本机易失事件失败不得回滚已经完成的 OSS 上传。
        }
    }

    private void recordImagePublishCheckpoint(
            AiConversationStreamTimingContext timingContext,
            String generationPublicId,
            AiConversationGeneratedImage image,
            String checkpoint,
            AiConversationImagePreviewPublishResult publishResult) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("generationPublicId", generationPublicId);
        details.put("checkpoint", checkpoint);
        details.put("outputIndex", image.outputIndex());
        details.put("mappedPhase", image.phase().name());
        if (image.partialImageIndex() != null) {
            details.put("partialImageIndex", image.partialImageIndex());
        }
        details.put("bytes", image.sizeBytes());
        if (publishResult != null) {
            details.put("accepted", publishResult.accepted());
            details.put("retained", publishResult.retained());
            details.put("observerCount", publishResult.observerCount());
        }
        transportDiagnosticService.recordSafely(
                timingContext,
                "ai_image_stream_checkpoint",
                details);
    }

    private CompletableFuture<Void> preparePreviewSafely(
            WorkerState state,
            AiConversationStreamTimingContext timingContext,
            String generationPublicId,
            AiConversationGeneratedImage image) {
        long previewSequence = state.registerPreview(image.outputIndex());
        recordImagePublishCheckpoint(
                timingContext,
                generationPublicId,
                image,
                "P4_PREVIEW_PREPARATION_ATTEMPT",
                null);
        try {
            return imagePreviewProcessor.prepare(image)
                    .thenAccept(prepared -> {
                        AiConversationImagePreviewPublishResult publishResult =
                                prepared.filter(ignored -> state.isLatestPreview(
                                                image.outputIndex(), previewSequence))
                                        .map(preview -> publishPreviewSafely(
                                                generationPublicId, preview))
                                        .orElseGet(
                                                AiConversationImagePreviewPublishResult::ignored);
                        recordImagePublishCheckpoint(
                                timingContext,
                                generationPublicId,
                                image,
                                "P5_PREVIEW_PUBLISH_RESULT",
                                publishResult);
                    })
                    .exceptionally(ignored -> {
                        recordImagePublishCheckpoint(
                                timingContext,
                                generationPublicId,
                                image,
                                "P5_PREVIEW_PUBLISH_RESULT",
                                AiConversationImagePreviewPublishResult.ignored());
                        return null;
                    });
        } catch (RuntimeException ignored) {
            recordImagePublishCheckpoint(
                    timingContext,
                    generationPublicId,
                    image,
                    "P5_PREVIEW_PUBLISH_RESULT",
                    AiConversationImagePreviewPublishResult.ignored());
            return CompletableFuture.completedFuture(null);
        }
    }

    private AiConversationImagePreviewPublishResult publishPreviewSafely(
            String generationPublicId,
            AiConversationPreparedImagePreview preview) {
        try {
            return imagePreviewBroker.publish(generationPublicId, preview);
        } catch (RuntimeException ignored) {
            // Broker 只承载本机临时预览，发布失败不能把已经收到的模型图片改判为业务失败。
            return AiConversationImagePreviewPublishResult.ignored();
        }
    }

    private static void awaitImagePreviewTasks(
            WorkerState state,
            long workerDeadlineNanos) throws InterruptedException {
        CompletableFuture<?>[] tasks = state.imagePreviewTasks.values().toArray(
                CompletableFuture[]::new);
        if (tasks.length == 0) {
            return;
        }
        long remaining = remainingNanos(workerDeadlineNanos);
        long available = remaining == Long.MAX_VALUE
                ? TimeUnit.SECONDS.toNanos(3)
                : Math.max(0L, remaining - TimeUnit.SECONDS.toNanos(1));
        long waitNanos = Math.min(
                available,
                TimeUnit.SECONDS.toNanos(3));
        if (waitNanos <= 0L) {
            return;
        }
        try {
            // 只给易失预览一个很短的收敛窗口，不能让压缩失败阻塞权威 OSS 持久化和终态结算。
            CompletableFuture.allOf(tasks).get(waitNanos, TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException ignored) {
            // 未完成任务仍可在 Broker 宽限期内发布；超时不会升级为生成失败。
        }
    }

    private void publishPreviewFailureSafely(
            WorkerState state,
            String generationPublicId,
            short outputIndex,
            String reasonCode) {
        state.invalidatePreview(outputIndex);
        try {
            imagePreviewBroker.publishFailure(
                    generationPublicId, outputIndex, reasonCode);
        } catch (RuntimeException ignored) {
            // 失败槽位仍由 Generation 终态和正式附件表达，临时 Broker 不得覆盖该权威结果。
        }
    }

    /**
     * 进度事件仅影响当前 SSE 展示；输出通道暂时不可用时不能反向中断已经开始的 OSS 搬运。
     */
    private void publishMediaUploadProgressSafely(
            String generationPublicId,
            AiConversationMediaUploadProgress progress) {
        try {
            mediaUploadProgressPublisher.publish(generationPublicId, progress);
        } catch (RuntimeException ignored) {
            // 最终视频附件仍由 FC 结果和数据库状态决定，临时进度允许丢失。
        }
    }

    private void freezeCompletedVideo(
            String generationPublicId,
            AiConversationGeneration generation,
            String conversationPublicId,
            WorkerState state,
            String traceId) {
        AiConversationGeneratedVideo generated = Objects.requireNonNull(
                state.generatedVideo.get(),
                "Completed video result is unavailable.");
        AiConversationVideoTransferService transferPort = Objects.requireNonNull(
                videoTransferService,
                "Video transfer service is unavailable.");
        AiConversationVideoObjectKeyFactory keyFactory = Objects.requireNonNull(
                videoObjectKeyFactory,
                "Video object-key factory is unavailable.");
        if (state.reservedMessageId <= 0L) {
            throw new IllegalStateException("Video message ID was not reserved.");
        }
        String attachmentId = cn.hutool.core.lang.id.NanoId.randomNanoId(38);
        String userPublicId = publicIdCodec.encode(generation.getLoginIdentityId());
        String messagePublicId = publicIdCodec.encode(state.reservedMessageId);
        String objectKey = keyFactory.create(
                userPublicId,
                conversationPublicId,
                messagePublicId,
                attachmentId);
		controlService.updateVideoStage(
				generation.getId(), AiConversationVideoGenerationStage.OSS_TRANSFERRING);
		state.videoTransferStarted.set(true);
		outputStore.publishEvent(
                generationPublicId,
                "video_transfer_started",
                json(Map.of("stage", "OSS_TRANSFERRING")));
		long videoTransferStartedNanos = System.nanoTime();
		AiConversationVideoTransferResult transferred;
		try {
			transferred = transferPort.transfer(
                new AiConversationVideoTransferCommand(
                        cn.hutool.core.lang.id.NanoId.randomNanoId(38),
                        generated.ephemeralUrl(),
                        objectKey,
                        "video/mp4",
						videoProperties.functionCompute().maximumVideoBytes()),
				progress -> publishMediaUploadProgressSafely(
						generationPublicId, progress));
			metrics.mediaUpload(Duration.ofNanos(
					System.nanoTime() - videoTransferStartedNanos), "video", "success");
		} catch (RuntimeException failure) {
			metrics.mediaUpload(Duration.ofNanos(
					System.nanoTime() - videoTransferStartedNanos), "video", "failed");
			throw failure;
		}
		controlService.updateVideoStage(
				generation.getId(), AiConversationVideoGenerationStage.OSS_READY);
        AiConversationAttachment attachment = AiConversationAttachment.available(
                attachmentId,
                "generated-video.mp4",
                transferred.contentType(),
                transferred.byteSize(),
                AiConversationAttachmentCategory.VIDEO,
                transferred.publicUrl());
        AiConversationPersistedVideoResult persisted =
                new AiConversationPersistedVideoResult(
                        attachment,
                        transferred.durationMillis(),
                        transferred.width(),
                        transferred.height(),
                        transferred.contentType(),
                        transferred.byteSize(),
                        transferred.videoCodec(),
                        transferred.objectKey(),
                        "ALIYUN_OSS");
        AiConversationGenerationWorkItem beforeTerminal = controlService.load(
                generation.getId());
        if (isCancellationRequested(beforeTerminal)) {
            requiredImageDependency(attachmentService)
                    .compensateCreatedObjects(List.of(transferred.objectKey()));
            markInterrupted(beforeTerminal, conversationPublicId, state);
            freezeCancellation(beforeTerminal, "", state.usage.get(), state, traceId);
            return;
        }
        AiConversationGenerationTerminalResult terminal;
        try {
            terminal = terminalService.freeze(
                    new AiConversationGenerationTerminalCommand(
                            generation.getId(),
                            AiConversationGenerationTerminalType.COMPLETED,
                            "VIDEO_OSS_READY",
                            "",
                            persistedVideoResultCodec.encode(persisted),
                            state.usage.get(),
                            meteringEvidenceJson(state),
                            state.finishReason.get(),
                            state.upstreamRequestId.get(),
                            traceId));
        } catch (RuntimeException failure) {
            requiredImageDependency(attachmentService)
                    .compensateCreatedObjects(List.of(transferred.objectKey()));
            throw failure;
        }
        if (!terminal.claimed()
                || !AiConversationGenerationTerminalType.COMPLETED.name()
                        .equals(terminal.terminalType())) {
            requiredImageDependency(attachmentService)
                    .compensateCreatedObjects(List.of(transferred.objectKey()));
            return;
        }
        // 终态已经冻结后，阶段标记失败只能交由恢复/告警处理，禁止再删除终态引用的 OSS 正式对象。
        controlService.updateVideoStage(
                generation.getId(), AiConversationVideoGenerationStage.SUCCEEDED);
    }

    private void freezeCompletedImage(
            String generationPublicId,
            AiConversationGeneration generation,
            String conversationPublicId,
            WorkerState state,
            short requestedOutputCount,
            long workerDeadlineNanos,
            String traceId) throws InterruptedException {
        if (state.finalImages.isEmpty()) {
            throw new IllegalStateException(
                    "AI image stream completed without a final image");
        }
        AiConversationGeneratedUploadSession uploadSession = Objects.requireNonNull(
                state.imageUploadSession,
                "Image upload session is unavailable");
        List<AiConversationGeneratedUploadResult> uploadResults = uploadSession.finish(
                remainingDuration(workerDeadlineNanos));
        awaitImageUploadTasks(state, workerDeadlineNanos);
        Throwable lifecycleFailure = state.failure.get();
        if (lifecycleFailure != null) {
            throw lifecycleFailure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException(
                            "AI Generation lifecycle failed during OSS finalization.",
                            lifecycleFailure);
        }
        if (remainingNanos(workerDeadlineNanos) <= 0L) {
            throw new IllegalStateException("AI Generation worker deadline expired during OSS finalization.");
        }
        AiConversationGenerationWorkItem beforeTerminal = controlService.load(generation.getId());
        if (isCancellationRequested(beforeTerminal)) {
            // Stop 在 OSS 上传期间到达时，刚创建的对象尚无数据库引用，必须先补偿再冻结取消事实。
            abortImageUploadsSafely(state);
            markInterrupted(beforeTerminal, conversationPublicId, state);
            freezeCancellation(beforeTerminal, "", state.usage.get(), state, traceId);
            return;
        }
        List<AiConversationAttachment> persisted = uploadResults.stream()
                .filter(AiConversationGeneratedUploadResult::successful)
                .map(AiConversationGeneratedUploadResult::attachment)
                .limit(10)
                .toList();
        if (persisted.isEmpty()) {
            throw new IllegalStateException("AI image OSS finalization produced no persistent output.");
        }
        // 缩略图派生与原图上传并行；只在冻结 completed 前短暂收敛，避免终态先清空页面预览通道。
        awaitImagePreviewTasks(state, workerDeadlineNanos);
        state.usage.set(state.aggregateImageUsage());
        try {
            AiConversationGenerationTerminalResult terminal = terminalService.freeze(
                    new AiConversationGenerationTerminalCommand(
                            generation.getId(),
                            AiConversationGenerationTerminalType.COMPLETED,
                            persisted.size() == requestedOutputCount
                                    ? "IMAGE_COMPLETED"
                                    : "IMAGE_PARTIAL_COMPLETED",
                            "",
                            generatedAttachmentCodec.encode(persisted),
                            state.usage.get(),
                            meteringEvidenceJson(state),
                            state.finishReason.get(),
                            state.upstreamRequestId.get(),
                            traceId));
            if (terminal.claimed()
                    && AiConversationGenerationTerminalType.COMPLETED.name()
                            .equals(terminal.terminalType())) {
                // 只有 URL 已经进入权威终态证据后才能解除 Session 的补偿责任。
                uploadSession.commit();
            } else {
                // 取消请求或另一个终态赢得行锁时，本 Worker 创建的对象没有数据库引用，必须立即补偿删除。
                abortImageUploadsSafely(state);
            }
        } catch (RuntimeException failure) {
            // 终态事务回滚后 URL 不会进入结算证据，刚上传的对象同样必须丢弃。
            abortImageUploadsSafely(state);
            throw failure;
        }
    }

    private static void awaitImageUploadTasks(
            WorkerState state,
            long workerDeadlineNanos) throws InterruptedException {
        CompletableFuture<?>[] tasks = state.imageUploadTasks.values().toArray(
                CompletableFuture[]::new);
        if (tasks.length == 0) {
            return;
        }
        long remaining = remainingNanos(workerDeadlineNanos);
        if (remaining <= 0L) {
            throw new IllegalStateException(
                    "AI Generation worker deadline expired during image upload callbacks.");
        }
        try {
            CompletableFuture.allOf(tasks).get(remaining, TimeUnit.NANOSECONDS);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "Generated image upload callback failed.", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException(
                    "Generated image upload callbacks timed out.", exception);
        }
    }

    private static void abortImageUploadsSafely(WorkerState state) {
        AiConversationGeneratedUploadSession uploadSession = state.imageUploadSession;
        if (uploadSession == null) {
            return;
        }
        try {
            uploadSession.abortAndCompensate();
        } catch (RuntimeException ignored) {
            // OSS 补偿已有独立失败指标；finally 兜底不得覆盖已经冻结或即将冻结的业务终态。
        }
    }

    private static boolean isCancellationRequested(AiConversationGenerationWorkItem workItem) {
        return workItem != null
                && workItem.generation().getGenerationStatus()
                == AiConversationGenerationStatus.CANCEL_REQUESTED.code();
    }

    private static long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        try {
            return Math.addExact(now, timeout.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : deadlineNanos - System.nanoTime();
    }

    private static Duration remainingDuration(long deadlineNanos) {
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0L) {
            throw new IllegalStateException("AI Generation worker deadline expired.");
        }
        return Duration.ofNanos(remaining);
    }

    private static <T> T requiredImageDependency(T dependency) {
        return Objects.requireNonNull(
                dependency,
                "Image generation dependency is unavailable");
    }

    private static void requireMeteringBasis(
            AiConversationGenerationPayload payload,
            AiConversationMeteringBasis expected) {
        if (payload.getMeteringBasis() == null
                || payload.getMeteringBasis() != expected.code()) {
            throw new IllegalStateException(
                    "AI Generation metering basis does not match its strategy.");
        }
    }

    private boolean freezeCancellation(
            AiConversationGenerationWorkItem workItem,
            String assistantText,
            AiConversationMeteredUsage usage,
            String traceId) {
        return freezeCancellation(
                workItem, assistantText, usage, null, traceId);
    }

    private boolean freezeCancellation(
            AiConversationGenerationWorkItem workItem,
            String assistantText,
            AiConversationMeteredUsage usage,
            WorkerState state,
            String traceId) {
        String source = workItem.generation().getCancelSource();
        AiConversationGenerationTerminalType type = AiConversationGenerationCancelSource.ADMIN_CANCEL.name()
                        .equals(source)
                ? AiConversationGenerationTerminalType.ADMIN_CANCELLED
                : AiConversationGenerationTerminalType.CLIENT_CANCELLED;
        return terminalService.freeze(new AiConversationGenerationTerminalCommand(
                workItem.generation().getId(),
                type,
                Objects.requireNonNullElse(source, "CLIENT_EXIT_TIMEOUT"),
                assistantText,
                "[]",
                usage,
                state == null ? null : meteringEvidenceJson(state),
                "CLIENT_CANCELLED",
                state == null ? null : state.upstreamRequestId.get(),
                traceId)).claimed();
    }

	private boolean freezeFailure(
            AiConversationGenerationWorkItem workItem,
            AiConversationGenerationTerminalType type,
            String reason,
            String assistantText,
            AiConversationMeteredUsage usage,
			WorkerState state,
			String traceId) {
		if (workItem.generation().getVideoStage() != null) {
			controlService.updateVideoStage(
					workItem.generation().getId(),
					videoFailureStage(reason, state.videoTransferStarted.get()));
		}
		return terminalService.freeze(new AiConversationGenerationTerminalCommand(
                workItem.generation().getId(),
                type,
                reason,
                assistantText,
                "[]",
                usage,
                meteringEvidenceJson(state),
                type.name(),
                state.upstreamRequestId.get(),
                traceId)).claimed();
	}

	private static AiConversationVideoGenerationStage videoFailureStage(
			String reason,
			boolean videoTransferStarted) {
		if (videoTransferStarted) {
			return AiConversationVideoGenerationStage.OSS_TRANSFER_FAILED;
		}
		return switch (reason) {
			case "AI_VIDEO_XAI_REJECTED" -> AiConversationVideoGenerationStage.XAI_REJECTED;
			case "AI_VIDEO_XAI_EXPIRED" -> AiConversationVideoGenerationStage.XAI_EXPIRED;
			case "AI_VIDEO_XAI_RESULT_UNCERTAIN", "AI_GENERATION_WORKER_TIMEOUT" ->
					AiConversationVideoGenerationStage.XAI_RESULT_UNCERTAIN;
			case "AI_VIDEO_OSS_TRANSFER_FAILED" ->
					AiConversationVideoGenerationStage.OSS_TRANSFER_FAILED;
			default -> AiConversationVideoGenerationStage.XAI_FAILED;
		};
	}

    private boolean freezeFailureOrCancellation(
            AiConversationGenerationWorkItem original,
            AiConversationGenerationTerminalType failureType,
            String failureCode,
            String assistantText,
            AiConversationMeteredUsage usage,
            WorkerState state,
            String traceId) {
        AiConversationGenerationWorkItem latest = controlService.load(
                original.generation().getId());
        if (latest != null
                && latest.generation().getGenerationStatus()
                == AiConversationGenerationStatus.CANCEL_REQUESTED.code()) {
            return freezeCancellation(latest, assistantText, usage, state, traceId);
        }
        return freezeFailure(
                original,
                failureType,
                failureCode,
                assistantText,
                usage,
                state,
                traceId);
    }

    private void markInterrupted(
            AiConversationGenerationWorkItem workItem,
            String conversationPublicId,
            WorkerState state) {
        if (state.cacheGeneration == null || state.ephemeralOrdinal <= 0L) {
            return;
        }
        try {
            AiConversationInterruptionSource source = interruptionSource(
                    workItem.generation().getCancelSource());
            AiConversationContextWriteOutcome outcome =
                    contextStore.saveInterruptedTurn(
                            conversationPublicId,
                            state.cacheGeneration,
                            state.ephemeralOrdinal,
                            utf8Chunks(
                                    state.answer.toString(),
                                    conversationProperties.streamFlushBytes()),
                            source);
            if (outcome == AiConversationContextWriteOutcome.APPLIED
                    && source == AiConversationInterruptionSource.USER_STOP) {
                compactionCoordinator.request(
                        workItem.generation().getConversationId(),
                        conversationPublicId,
                        workItem.generation().getModelId(),
                        AiConversationCompactionTrigger.USER_STOP);
            }
        } catch (RuntimeException ignoredFailure) {
            // Redis 草稿状态是派生数据，失败不能阻止 PostgreSQL 终态和退款。
        }
    }

    private void preserveUserStopBeforeStart(
            AiConversationGenerationWorkItem workItem) {
        if (interruptionSource(workItem.generation().getCancelSource())
                != AiConversationInterruptionSource.USER_STOP) {
            return;
        }
        try {
            AiConversationGeneration generation = workItem.generation();
            AiConversationGenerationPayload payload = workItem.payload();
            String conversationPublicId = idCodec.encode(
                    generation.getConversationId());
            AiConversationGenerationInputSnapshot inputSnapshot =
                    inputCodec.decode(payload.getInputAttachmentsJson());
            AiConversationContent input = new AiConversationContent(
                    payload.getInputText(), inputSnapshot.attachments());
            // Worker 尚未建立上游流时没有缓存游标；先在最新 generation 中创建用户 Turn，
            // 再以 USER_STOP 原子提交空的部分回答，确保明确的用户意图仍进入后续上下文。
            for (int attempt = 0; attempt < 2; attempt++) {
                AiConversationContextSnapshot snapshot = contextService.load(
                        generation.getConversationId(), conversationPublicId);
                AiConversationEphemeralStart ephemeral =
                        contextStore.appendEphemeralUser(
                                conversationPublicId,
                                snapshot.generation(),
                                idCodec.encode(generation.getUsageId()),
                                input);
                if (ephemeral.outcome()
                        == AiConversationContextWriteOutcome.GENERATION_MISMATCH) {
                    continue;
                }
                if (ephemeral.outcome()
                        != AiConversationContextWriteOutcome.APPLIED) {
                    return;
                }
                AiConversationContextWriteOutcome stopped =
                        contextStore.saveInterruptedTurn(
                                conversationPublicId,
                                snapshot.generation(),
                                ephemeral.ordinal(),
                                List.of(),
                                AiConversationInterruptionSource.USER_STOP);
                if (stopped == AiConversationContextWriteOutcome.APPLIED) {
                    compactionCoordinator.request(
                            generation.getConversationId(),
                            conversationPublicId,
                            generation.getModelId(),
                            AiConversationCompactionTrigger.USER_STOP);
                }
                return;
            }
        } catch (RuntimeException ignoredFailure) {
            // Redis 快照是可重建派生层；保存零输出 Stop 失败不能阻止权威取消和退款终态。
        }
    }

    private static AiConversationInterruptionSource interruptionSource(
            String cancelSource) {
        if (AiConversationGenerationCancelSource.USER_STOP.name()
                .equals(cancelSource)) {
            return AiConversationInterruptionSource.USER_STOP;
        }
        if (AiConversationGenerationCancelSource.CLIENT_EXIT_TIMEOUT.name()
                .equals(cancelSource)) {
            return AiConversationInterruptionSource.TRANSPORT_DISCONNECT;
        }
        return AiConversationInterruptionSource.SYSTEM_FAILURE;
    }

    private static List<String> utf8Chunks(String value, int maxBytes) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            String unit = new String(Character.toChars(codePoint));
            int unitBytes = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 0 && bytes + unitBytes > maxBytes) {
                chunks.add(current.toString());
                current.setLength(0);
                bytes = 0;
            }
            current.append(unit);
            bytes += unitBytes;
            index += Character.charCount(codePoint);
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return List.copyOf(chunks);
    }

    private AiModelCacheEntry requiredModel(long modelId) {
        return modelCacheService.getOrLoadEnabledSnapshot().models().stream()
                .filter(candidate -> candidate.id() == modelId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("AI Generation model is unavailable."));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AI Generation terminal evidence is invalid.", exception);
        }
    }

    private static void appendBounded(WorkerState state, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int newBytes = Math.addExact(
                state.answerBytes,
                text.getBytes(StandardCharsets.UTF_8).length);
        if (newBytes > MAX_ASSISTANT_BYTES) {
            throw new IllegalStateException("AI Generation assistant output exceeds the safety limit.");
        }
        state.answer.append(text);
        state.answerBytes = newBytes;
    }

	private static AiConversationGenerationTerminalType upstreamFailure(Throwable failure) {
		if (failure instanceof AiConversationException exception
				&& (exception.code().name().startsWith("AI_UPSTREAM_")
						|| exception.code().name().startsWith("AI_VIDEO_XAI_"))) {
            return AiConversationGenerationTerminalType.UPSTREAM_FAILED;
        }
        return AiConversationGenerationTerminalType.SYSTEM_FAILED;
    }

    private static boolean isRuntimeLinkageFailure(Throwable failure) {
        return failure instanceof AiConversationException exception
                && exception.code()
                        == AiConversationErrorCode.AI_RUNTIME_LINKAGE_FAILED;
    }

    private static String failureCode(Throwable failure) {
        return failure instanceof AiConversationException exception
                ? exception.code().name()
                : "AI_GENERATION_SYSTEM_FAILED";
    }

    private static String boundedUpstreamRequestId(String value) {
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9._:-]{1,128}")
                ? normalized
                : "unavailable";
    }

    private static String requiredUpstreamRequestId(String value) {
        String normalized = normalizedUpstreamRequestId(value);
        if (normalized == null) {
            throw new IllegalArgumentException("AI upstream request ID is invalid.");
        }
        return normalized;
    }

    private static String normalizedUpstreamRequestId(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.matches("[A-Za-z0-9._:-]{1,128}")
                ? normalized
                : null;
    }

    private String meteringEvidenceJson(WorkerState state) {
        AiConversationVideoMeteringEvidence videoEvidence =
                state.videoMeteringEvidence.get();
        if (videoEvidence != null) {
            var root = objectMapper.createObjectNode();
            root.put("schemaVersion", 1);
            root.put("basis", AiConversationMeteringBasis.PROVIDER_COST_TICKS.name());
            root.put("kind", "VIDEO");
            root.put("requestId", videoEvidence.requestId());
            if (videoEvidence.costInUsdTicks() == null) {
                root.putNull("costTicks");
            } else {
                root.put("costTicks", Long.toString(
                        videoEvidence.costInUsdTicks()));
            }
            return root.toString();
        }
        if (state.imageMeteringEvidence.isEmpty()) {
            return null;
        }
        var root = objectMapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("basis", AiConversationMeteringBasis.PROVIDER_COST_TICKS.name());
        var outputs = root.putArray("outputs");
        state.imageMeteringEvidence.values().stream()
                .sorted(java.util.Comparator.comparingInt(
                        AiConversationImageMeteringEvidence::outputIndex))
                .forEach(evidence -> {
                    var output = outputs.addObject();
                    output.put("outputIndex", evidence.outputIndex());
                    output.put("status", evidence.status().name());
                    output.put("requestId", evidence.requestId());
                    if (evidence.costTicks() == null) {
                        output.putNull("costTicks");
                    } else {
                        output.put("costTicks", Long.toString(evidence.costTicks()));
                    }
                });
        return root.toString();
    }

    private static final class WorkerState {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final StringBuilder answer = new StringBuilder();
        private final List<AiConversationGeneratedMedia> generatedMedia = new ArrayList<>();
        private final AtomicReference<AiConversationMeteredUsage> usage =
                new AtomicReference<>();
        private final AtomicReference<String> finishReason = new AtomicReference<>();
        private final AtomicReference<String> upstreamRequestId = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<SignalType> signal = new AtomicReference<>();
        private final AtomicReference<AiConversationGeneratedVideo> generatedVideo =
                new AtomicReference<>();
		private final AtomicReference<AiConversationVideoMeteringEvidence>
				videoMeteringEvidence = new AtomicReference<>();
		private final AtomicBoolean videoPendingStage = new AtomicBoolean();
		private final AtomicBoolean videoTransferStarted = new AtomicBoolean();
		private byte[] generationId;
        private final ConcurrentSkipListMap<Short, AiConversationGeneratedImage>
                finalImages = new ConcurrentSkipListMap<>();
        private final ConcurrentMap<Short, AiConversationMeteredUsage> imageUsages =
                new ConcurrentHashMap<>();
        private final ConcurrentSkipListMap<Short, AiConversationImageMeteringEvidence>
                imageMeteringEvidence = new ConcurrentSkipListMap<>();
        private final ConcurrentMap<Short, Throwable> imageFailures =
                new ConcurrentHashMap<>();
        private final ConcurrentSkipListMap<Short, String> upstreamRequestIds =
                new ConcurrentSkipListMap<>();
        private final ConcurrentMap<Short, CompletableFuture<Void>> imagePreviewTasks =
                new ConcurrentHashMap<>();
        private final ConcurrentMap<Short, CompletableFuture<AiConversationGeneratedUploadResult>>
                imageUploadTasks = new ConcurrentHashMap<>();
        private final ConcurrentSkipListMap<Short, AiConversationGeneratedUploadResult>
                persistedImages = new ConcurrentSkipListMap<>();
        private final ConcurrentMap<Short, Long> latestPreviewSequences =
                new ConcurrentHashMap<>();
        private final AtomicLong previewSequence = new AtomicLong();
        private final AtomicLong finalImageBytes = new AtomicLong();
        private final long maximumFinalImageBytes;
        private AiConversationGeneratedUploadSession imageUploadSession;
        private long reservedMessageId;
        private String cacheGeneration;
        private long ephemeralOrdinal;
        private int answerBytes;

        private WorkerState(long maximumFinalImageBytes) {
            this.maximumFinalImageBytes = maximumFinalImageBytes;
        }

        /**
         * 每个槽位记录全局单调序号，确保异步完成顺序不能让旧 PARTIAL 覆盖新 FINAL 或失败状态。
         */
        private long registerPreview(short outputIndex) {
            long sequence = previewSequence.incrementAndGet();
            latestPreviewSequences.put(outputIndex, sequence);
            return sequence;
        }

        private boolean isLatestPreview(short outputIndex, long sequence) {
            return Objects.equals(latestPreviewSequences.get(outputIndex), sequence);
        }

        private void invalidatePreview(short outputIndex) {
            latestPreviewSequences.put(outputIndex, previewSequence.incrementAndGet());
        }

        private boolean reserveFinalBytes(AiConversationGeneratedImage image) {
            long total = finalImageBytes.addAndGet(image.sizeBytes());
            if (total <= maximumFinalImageBytes) {
                return true;
            }
            finalImageBytes.addAndGet(-image.sizeBytes());
            return false;
        }

        private void removeFinalImage(short outputIndex) {
            AiConversationGeneratedImage removed = finalImages.remove(outputIndex);
            if (removed != null) {
                finalImageBytes.addAndGet(-removed.sizeBytes());
            }
        }

        private void removeFinalImage(
                short outputIndex,
                AiConversationGeneratedImage expected) {
            if (finalImages.remove(outputIndex, expected)) {
                finalImageBytes.addAndGet(-expected.sizeBytes());
            }
        }

        /**
         * 每个 outputIndex 只允许计入一次权威 Usage，并用精确加法把任何溢出转成受控系统失败。
         */
        private AiConversationMeteredUsage aggregateImageUsage() {
            if (finalImages.isEmpty()) {
                return null;
            }
            AiConversationMeteredUsage first = imageUsages.get(
                    finalImages.firstKey());
            if (first == null) {
                return null;
            }
            if (first instanceof AiConversationProviderCostUsage) {
                long totalTicks = 0L;
                for (Short outputIndex : finalImages.keySet()) {
                    AiConversationMeteredUsage item = imageUsages.get(outputIndex);
                    if (!(item instanceof AiConversationProviderCostUsage costUsage)) {
                        return null;
                    }
                    totalTicks = Math.addExact(
                            totalTicks, costUsage.costInUsdTicks());
                }
                return new AiConversationProviderCostUsage(totalTicks);
            }
            long prompt = 0L;
            long cached = 0L;
            long completion = 0L;
            long reasoning = 0L;
            for (Short outputIndex : finalImages.keySet()) {
                AiConversationMeteredUsage metered = imageUsages.get(outputIndex);
                if (!(metered instanceof AiConversationUsage item)) {
                    return null;
                }
                prompt = Math.addExact(prompt, item.promptTokens());
                cached = Math.addExact(cached, item.cachedPromptTokens());
                completion = Math.addExact(completion, item.completionTokens());
                reasoning = Math.addExact(reasoning, item.reasoningTokens());
            }
            return new AiConversationUsage(
                    prompt, cached, completion, reasoning);
        }
    }
}
