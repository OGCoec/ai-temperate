package com.example.temperate.service.user.aiconversation.generation.worker.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.model.ai.entity.AiConversationGeneration;
import com.example.temperate.model.ai.entity.AiConversationGenerationPayload;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyPermit;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyService;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationEphemeralStart;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingClock;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTransportDiagnosticService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationCancelSource;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStatus;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalCommand;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationTerminalType;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationOutputStore;
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
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImageFormat;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationPersistedGeneratedAttachmentCodec;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategyRegistry;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamBatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.SynchronousSink;

/**
 * 在 Rabbit Worker 生命周期内独立持有模型 SDK 流，批量写 Redis 快照，并依据完成、显式取消或异常冻结唯一终态。
 *
 * <p>SSE Observer 从不持有本订阅；上游或系统异常即使已有部分输出也冻结失败终态并由 Billing 全额退款。</p>
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
    private final AiConversationImagePreviewBroker imagePreviewBroker;
    private final AiConversationPersistedGeneratedAttachmentCodec generatedAttachmentCodec;

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
            ObjectMapper objectMapper) {
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
                AiConversationImagePreviewBroker.noOp(),
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper));
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
            AiConversationStreamTransportDiagnosticService transportDiagnosticService) {
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
                AiConversationImagePreviewBroker.noOp(),
                new AiConversationPersistedGeneratedAttachmentCodec(objectMapper));
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
            AiConversationImagePreviewBroker imagePreviewBroker,
            AiConversationPersistedGeneratedAttachmentCodec generatedAttachmentCodec) {
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
        this.imagePreviewBroker = Objects.requireNonNull(imagePreviewBroker);
        this.generatedAttachmentCodec = Objects.requireNonNull(generatedAttachmentCodec);
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
        WorkerState state = new WorkerState();
        try {
            AiModelCacheEntry model = requiredModel(generation.getModelId());
            AiConversationGenerationInputSnapshot inputSnapshot =
                    inputCodec.decode(payload.getInputAttachmentsJson());
            AiConversationContent input = new AiConversationContent(
                    payload.getInputText(), inputSnapshot.attachments());
            AiConversationPromptSnapshot prompt = contextService.prepare(
                    generation.getConversationId(), conversationPublicId, model, input);
            permit = concurrencyService.tryAcquire(generation.getLoginIdentityId())
                    .orElseThrow(() -> new IllegalStateException(
                            "AI Generation concurrency permit is unavailable."));
            lease = leaseService.tryAcquire(
                            conversationPublicId, AiConversationLeaseType.INFLIGHT)
                    .orElseThrow(() -> new IllegalStateException(
                            "AI Generation conversation lease is unavailable."));
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
                markInterrupted(conversationPublicId, state);
                freezeCancellation(beforeUpstream, state.answer.toString(), state.usage.get(), traceId);
                return;
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
            AiConversationImageGenerationOptions imageGeneration =
                    inputSnapshot.imageGeneration();
            AiConversationModelRequest modelRequest =
                    new AiConversationModelRequest(
                            model.modelName(),
                            model.maxOutputTokens(),
                            AiConversationReasoningEffort.fromLevel(
                            payload.getReasoningEffort().shortValue()),
                            prompt,
                            imageGeneration);
            Flux<AiConversationModelEvent> modelEvents = imageGeneration == null
                    ? modelClient.stream(modelRequest)
                            .map(AiConversationModelEvent.Chunk::new)
                    : requiredImageDependency(streamingStrategyRegistry)
                            .required(AiConversationStreamingProtocol
                                    .IMAGES_GENERATION)
                            .stream(new AiConversationStreamingRequest(
                                    modelRequest,
                                    com.example.temperate.service.user.aiconversation.response
                                            .AiConversationWebSearchMode.OFF));
            Flux<AiConversationModelChunk> upstream = modelEvents.handle(
                    (event, sink) -> acceptModelEvent(
                            generationPublicId, state, event, sink));
            AiConversationConcurrencyPermit acquiredPermit = permit;
            AiConversationLease acquiredLease = lease;
            // 后台 Worker 不再依赖 SSE 心跳，因此必须自行续租；任一租约丢失都转为系统失败并进入全额退款终态。
            Flux<AiConversationModelChunk> maintained = upstream.publish(shared -> Flux.merge(
                    shared,
                    Flux.interval(HEARTBEAT_INTERVAL)
                            .takeUntilOther(shared.ignoreElements())
                            .map(ignored -> {
                                if (!leaseService.renew(acquiredLease)
                                        || !concurrencyService.renew(acquiredPermit)) {
                                    throw new IllegalStateException(
                                            "AI Generation worker lease renewal failed.");
                                }
                                return new AiConversationModelChunk(
                                        "", null, null, null, List.of());
                            })));
            AtomicInteger redisChunk = new AtomicInteger();
            Flux<AiConversationModelChunk> batched = AiConversationStreamBatcher.forwardWhileBatching(
                    maintained,
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
                        transportDiagnosticService.record(
                                timingContext,
                                "ai_stream_worker_flush",
                                Map.of(
                                        "generationPublicId", generationPublicId,
                                        "flushReason", flushReason,
                                        "chunkCount", modelChunkBatch.size(),
                                        "deltaBytes", deltaBytes,
                                        "deltaChars", deltaChars));
                        List<String> chunks = modelChunkBatch.stream()
                                .map(AiConversationModelChunk::text)
                                .filter(text -> text != null && !text.isEmpty())
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
            subscription = timed
                    .doFinally(signal -> {
                        state.signal.set(signal);
                        state.finished.countDown();
                    })
                    .subscribe(
                            chunk -> acceptChunk(state, chunk),
                            state.failure::set);
            activeRegistry.register(generationPublicId, subscription);
            boolean finished = state.finished.await(
                    asyncProperties.maxWorkerDuration().toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!finished) {
                subscription.dispose();
                freezeFailureOrCancellation(
                        workItem,
                        AiConversationGenerationTerminalType.SYSTEM_FAILED,
                        "AI_GENERATION_WORKER_TIMEOUT",
                        state.answer.toString(),
                        state.usage.get(),
                        traceId);
                return;
            }
            AiConversationGenerationWorkItem latest = controlService.load(generation.getId());
            if (latest != null
                    && latest.generation().getGenerationStatus()
                    == AiConversationGenerationStatus.CANCEL_REQUESTED.code()) {
                markInterrupted(conversationPublicId, state);
                freezeCancellation(latest, state.answer.toString(), state.usage.get(), traceId);
            } else if (state.failure.get() != null) {
                markInterrupted(conversationPublicId, state);
                freezeFailure(
                        workItem,
                        upstreamFailure(state.failure.get()),
                        failureCode(state.failure.get()),
                        state.answer.toString(),
                        state.usage.get(),
                        traceId);
            } else if (state.signal.get() == SignalType.ON_COMPLETE
                    && state.usage.get() != null) {
                if (imageGeneration == null) {
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
                            generation,
                            conversationPublicId,
                            state,
                            traceId);
                }
            } else {
                markInterrupted(conversationPublicId, state);
                freezeFailure(
                        workItem,
                        AiConversationGenerationTerminalType.SYSTEM_FAILED,
                        "AI_STREAM_TERMINATED_WITHOUT_USAGE",
                        state.answer.toString(),
                        null,
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
                    traceId);
        } catch (RuntimeException failure) {
            boolean terminalClaimed = freezeFailureOrCancellation(
                    workItem,
                    upstreamFailure(failure),
                    failureCode(failure),
                    state.answer.toString(),
                    state.usage.get(),
                    traceId);
            if (!terminalClaimed) {
                // 终态已经由先前事务冻结时，原异常通常代表 Terminal Confirm 失败，必须阻止 Generation ACK。
                throw failure;
            }
        } finally {
            if (subscription != null) {
                // 线程中断或终态事务异常时上游可能仍未自然结束，必须先关闭订阅再释放 Registry 与租约。
                subscription.dispose();
                activeRegistry.remove(generationPublicId, subscription);
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
            WorkerState state,
            AiConversationModelEvent event,
            SynchronousSink<AiConversationModelChunk> sink) {
        if (event instanceof AiConversationModelEvent.Chunk chunk) {
            sink.next(chunk.value());
            return;
        }
        if (event instanceof AiConversationModelEvent.Image imageEvent) {
            AiConversationGeneratedImage image = imageEvent.value();
            imagePreviewBroker.publish(generationPublicId, image);
            metrics.imagePreview(image.phase().name());
            if (image.phase() == AiConversationGeneratedImagePhase.FINAL) {
                state.finalImage.set(image);
            }
            return;
        }
        if (event instanceof AiConversationModelEvent.Failure failure) {
            sink.error(new IllegalStateException(failure.reasonCode()));
        }
    }

    private void freezeCompletedImage(
            AiConversationGeneration generation,
            String conversationPublicId,
            WorkerState state,
            String traceId) {
        AiConversationGeneratedImage finalImage = state.finalImage.get();
        if (finalImage == null) {
            throw new IllegalStateException(
                    "AI image stream completed without a final image");
        }
        AiConversationGenerationBillingTransactionService billing =
                requiredImageDependency(billingTransactionService);
        AiConversationAttachmentService attachments =
                requiredImageDependency(attachmentService);
        long messageId = billing.getOrReserveMessageId(generation.getId());
        long persistenceStarted = System.nanoTime();
        AiConversationAttachmentFinalization finalized;
        try {
            // 最终文件名必须由已检测的真实 MIME 决定，确保 OSS Content-Type、URL 后缀和数据库元数据一致。
            String generatedFileName = AiConversationGeneratedImageFormat
                    .fromContentType(finalImage.contentType())
                    .generatedFileName();
            finalized = attachments.finalizeAttachments(
                    publicIdCodec.encode(generation.getLoginIdentityId()),
                    conversationPublicId,
                    publicIdCodec.encode(messageId),
                    List.of(),
                    List.of(new AiConversationGeneratedMedia(
                            generatedFileName,
                            finalImage.contentType(),
                            finalImage.bytes())));
        } catch (RuntimeException failure) {
            metrics.imagePersistence(
                    Duration.ofNanos(System.nanoTime() - persistenceStarted),
                    "failed");
            throw failure;
        }
        List<AiConversationAttachment> persisted = finalized.partialFailure()
                ? List.of()
                : finalized.responseAttachments().stream()
                        .filter(item -> item.state()
                                == com.example.temperate.service.user.aiconversation.attachment
                                        .AiConversationAttachmentState.AVAILABLE)
                        .limit(1)
                        .toList();
        if (persisted.isEmpty() && !finalized.createdObjectKeys().isEmpty()) {
            attachments.compensateCreatedObjects(finalized.createdObjectKeys());
        }
        metrics.imagePersistence(
                Duration.ofNanos(System.nanoTime() - persistenceStarted),
                persisted.isEmpty() ? "dropped" : "success");
        try {
            boolean claimed = terminalService.freeze(
                    new AiConversationGenerationTerminalCommand(
                            generation.getId(),
                            AiConversationGenerationTerminalType.COMPLETED,
                            persisted.isEmpty()
                                    ? "IMAGE_OSS_PERSISTENCE_DROPPED"
                                    : "IMAGE_COMPLETED",
                            "",
                            generatedAttachmentCodec.encode(persisted),
                            state.usage.get(),
                            state.finishReason.get(),
                            state.upstreamRequestId.get(),
                            traceId))
                    .claimed();
            if (!claimed && !finalized.createdObjectKeys().isEmpty()) {
                // 另一个终态已经赢得 CAS 时，本 Worker 创建的对象没有数据库引用，必须立即补偿删除。
                attachments.compensateCreatedObjects(finalized.createdObjectKeys());
            }
        } catch (RuntimeException failure) {
            // 终态事务回滚后 URL 不会进入结算证据，刚上传的对象同样必须丢弃。
            attachments.compensateCreatedObjects(finalized.createdObjectKeys());
            throw failure;
        }
    }

    private static <T> T requiredImageDependency(T dependency) {
        return Objects.requireNonNull(
                dependency,
                "Image generation dependency is unavailable");
    }

    private boolean freezeCancellation(
            AiConversationGenerationWorkItem workItem,
            String assistantText,
            AiConversationUsage usage,
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
                "CLIENT_CANCELLED",
                null,
                traceId)).claimed();
    }

    private boolean freezeFailure(
            AiConversationGenerationWorkItem workItem,
            AiConversationGenerationTerminalType type,
            String reason,
            String assistantText,
            AiConversationUsage usage,
            String traceId) {
        return terminalService.freeze(new AiConversationGenerationTerminalCommand(
                workItem.generation().getId(),
                type,
                reason,
                assistantText,
                "[]",
                usage,
                type.name(),
                null,
                traceId)).claimed();
    }

    private boolean freezeFailureOrCancellation(
            AiConversationGenerationWorkItem original,
            AiConversationGenerationTerminalType failureType,
            String failureCode,
            String assistantText,
            AiConversationUsage usage,
            String traceId) {
        AiConversationGenerationWorkItem latest = controlService.load(
                original.generation().getId());
        if (latest != null
                && latest.generation().getGenerationStatus()
                == AiConversationGenerationStatus.CANCEL_REQUESTED.code()) {
            return freezeCancellation(latest, assistantText, usage, traceId);
        }
        return freezeFailure(
                original,
                failureType,
                failureCode,
                assistantText,
                usage,
                traceId);
    }

    private void markInterrupted(String conversationPublicId, WorkerState state) {
        if (state.cacheGeneration == null || state.ephemeralOrdinal <= 0L) {
            return;
        }
        try {
            contextStore.markEphemeralInterrupted(
                    conversationPublicId, state.cacheGeneration, state.ephemeralOrdinal);
        } catch (RuntimeException ignoredFailure) {
            // Redis 草稿状态是派生数据，失败不能阻止 PostgreSQL 终态和退款。
        }
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
                && exception.code().name().startsWith("AI_UPSTREAM_")) {
            return AiConversationGenerationTerminalType.UPSTREAM_FAILED;
        }
        return AiConversationGenerationTerminalType.SYSTEM_FAILED;
    }

    private static String failureCode(Throwable failure) {
        return failure instanceof AiConversationException exception
                ? exception.code().name()
                : "AI_GENERATION_SYSTEM_FAILED";
    }

    private static final class WorkerState {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final StringBuilder answer = new StringBuilder();
        private final List<AiConversationGeneratedMedia> generatedMedia = new ArrayList<>();
        private final AtomicReference<AiConversationUsage> usage = new AtomicReference<>();
        private final AtomicReference<String> finishReason = new AtomicReference<>();
        private final AtomicReference<String> upstreamRequestId = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicReference<SignalType> signal = new AtomicReference<>();
        private final AtomicReference<AiConversationGeneratedImage> finalImage =
                new AtomicReference<>();
        private String cacheGeneration;
        private long ephemeralOrdinal;
        private int answerBytes;
    }
}
