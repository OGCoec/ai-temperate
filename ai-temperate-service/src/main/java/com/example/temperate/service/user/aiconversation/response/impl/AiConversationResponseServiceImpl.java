package com.example.temperate.service.user.aiconversation.response.impl;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiConversationMapper;
import com.example.temperate.mapper.ai.AiConversationMessageMapper;
import com.example.temperate.mapper.ai.AiModelUsageMapper;
import com.example.temperate.model.ai.entity.AiConversationMessage;
import com.example.temperate.model.ai.entity.AiModelUsage;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.aiconversation.billing.AiConversationBillingService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservation;
import com.example.temperate.service.user.aiconversation.billing.AiConversationReservationCommand;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementCommand;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementResult;
import com.example.temperate.service.user.aiconversation.billing.AiConversationSettlementService;
import com.example.temperate.service.user.aiconversation.billing.TokenReservationMetering;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionService;
import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationWebSearchProperties;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyPermit;
import com.example.temperate.service.user.aiconversation.concurrency.AiConversationConcurrencyService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextService;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextSnapshot;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextStore;
import com.example.temperate.service.user.aiconversation.context.AiConversationContextWriteOutcome;
import com.example.temperate.service.user.aiconversation.context.AiConversationEphemeralStart;
import com.example.temperate.service.user.aiconversation.context.AiConversationInterruptionSource;
import com.example.temperate.service.user.aiconversation.context.AiConversationPromptSnapshot;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleEvent;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationLifecycleTraceContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingBoundary;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingContext;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingDiagnosticService;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamTimingPath;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLease;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseService;
import com.example.temperate.service.user.aiconversation.lease.AiConversationLeaseType;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import com.example.temperate.service.user.aiconversation.model.AiConversationMeteringBasis;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelRequest;
import com.example.temperate.service.user.aiconversation.model.AiConversationUsage;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityPhase;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityStatus;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingProtocol;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingRequest;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategyRegistry;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationStreamingStrategy;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import com.example.temperate.service.user.aiconversation.response.AiConversationAcceptedData;
import com.example.temperate.service.user.aiconversation.response.AiConversationActivityData;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseActiveRegistry;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationHandle;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseControlStore;
import com.example.temperate.service.user.aiconversation.response.AiConversationCompletedData;
import com.example.temperate.service.user.aiconversation.response.AiConversationDeltaData;
import com.example.temperate.service.user.aiconversation.response.AiConversationErrorData;
import com.example.temperate.service.user.aiconversation.response.AiConversationInterruptionCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationInterruptionFinalizer;
import com.example.temperate.service.user.aiconversation.response.AiConversationRequestLifecycle;
import com.example.temperate.service.user.aiconversation.response.AiConversationRequestState;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseService;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseStream;
import com.example.temperate.service.user.aiconversation.response.AiConversationReasoningSummaryData;
import com.example.temperate.service.user.aiconversation.response.AiConversationSourceData;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingAction;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingDecision;
import com.example.temperate.service.user.aiconversation.response.AiConversationTerminalBillingPolicy;
import com.example.temperate.service.user.aiconversation.security.AiConversationIdempotencyHasher;
import com.example.temperate.service.user.aiconversation.text.AiConversationTextTokenizer;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import org.reactivestreams.Subscription;

/**
 * 编排一次用户发送动作，从幂等预扣到单次 8317 SSE，再到成功入库、系统失败退款或客户端取消结算。
 *
 * <p>数据库事务被隔离在 Billing、Settlement 和压缩持久化 Service 中；本实现持有的所有流式状态
 * 都是方法内局部对象，不会泄漏到单例 Bean。SSE delta 立即写向下游，Redis 只在终态保存完整草稿。</p>
 */
@Service
public final class AiConversationResponseServiceImpl
        implements AiConversationResponseService {

    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final int MAX_ASSISTANT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESEARCH_SOURCES = 200;
    private static final int MAX_INTERRUPTION_FINALIZATION_ATTEMPTS = 3;
    private static final TypeReference<List<AiConversationAttachment>> ATTACHMENT_LIST =
            new TypeReference<>() { };

    private final AiModelCacheService modelCacheService;
    private final AiConversationAttachmentService attachmentService;
    private final AiConversationAttachmentProperties attachmentProperties;
    private final AiConversationMapper conversationMapper;
    private final AiConversationMessageMapper messageMapper;
    private final AiConversationContextService contextService;
    private final AiConversationContextStore contextStore;
    private final AiConversationBillingService billingService;
    private final AiConversationSettlementService settlementService;
    private final AiConversationInterruptionFinalizer interruptionFinalizer;
    private final AiConversationTerminalBillingPolicy terminalBillingPolicy;
    private final AiConversationStreamFailureDiagnosticService
            failureDiagnosticService;
    private final AiConversationStreamTimingDiagnosticService
            timingDiagnosticService;
    private final AiConversationLifecycleDiagnosticService
            lifecycleDiagnosticService;
    private final AiConversationConcurrencyService concurrencyService;
    private final AiConversationLeaseService leaseService;
    private final AiConversationCompactionService compactionService;
    private final AiConversationStreamingStrategyRegistry streamingStrategies;
    private final AiConversationTextTokenizer tokenizer;
    private final AiConversationIdempotencyHasher idempotencyHasher;
    private final AiConversationDirectResponseActiveRegistry activeRegistry;
    private final AiConversationDirectResponseControlStore directControlStore;
    private final AiConversationAsyncGenerationProperties asyncGenerationProperties;
    private final AiConversationWebSearchProperties webSearchProperties;
    private final AiModelUsageMapper usageMapper;
    private final HybridBase64UrlCodec hybridIdCodec;
    private final PublicIdCodec publicIdCodec;
    private final AiConversationProperties properties;
    private final AiConversationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Executor finalizerExecutor;

    public AiConversationResponseServiceImpl(
            AiModelCacheService modelCacheService,
            AiConversationAttachmentService attachmentService,
            AiConversationAttachmentProperties attachmentProperties,
            AiConversationMapper conversationMapper,
            AiConversationMessageMapper messageMapper,
            AiConversationContextService contextService,
            AiConversationContextStore contextStore,
            AiConversationBillingService billingService,
            AiConversationSettlementService settlementService,
            AiConversationInterruptionFinalizer interruptionFinalizer,
            AiConversationTerminalBillingPolicy terminalBillingPolicy,
            AiConversationStreamFailureDiagnosticService
                    failureDiagnosticService,
            AiConversationStreamTimingDiagnosticService
                    timingDiagnosticService,
            AiConversationLifecycleDiagnosticService
                    lifecycleDiagnosticService,
            AiConversationConcurrencyService concurrencyService,
            AiConversationLeaseService leaseService,
            AiConversationCompactionService compactionService,
            AiConversationStreamingStrategyRegistry streamingStrategies,
            AiConversationTextTokenizer tokenizer,
            AiConversationIdempotencyHasher idempotencyHasher,
            AiConversationDirectResponseActiveRegistry activeRegistry,
            AiConversationDirectResponseControlStore directControlStore,
            AiConversationAsyncGenerationProperties asyncGenerationProperties,
            AiConversationWebSearchProperties webSearchProperties,
            AiModelUsageMapper usageMapper,
            HybridBase64UrlCodec hybridIdCodec,
            PublicIdCodec publicIdCodec,
            AiConversationProperties properties,
            AiConversationMetrics metrics,
            ObjectMapper objectMapper,
            @Qualifier("aiConversationFinalizerExecutor")
                    Executor finalizerExecutor) {
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
        this.attachmentService = Objects.requireNonNull(attachmentService);
        this.attachmentProperties = Objects.requireNonNull(attachmentProperties);
        this.conversationMapper = Objects.requireNonNull(conversationMapper);
        this.messageMapper = Objects.requireNonNull(messageMapper);
        this.contextService = Objects.requireNonNull(contextService);
        this.contextStore = Objects.requireNonNull(contextStore);
        this.billingService = Objects.requireNonNull(billingService);
        this.settlementService = Objects.requireNonNull(settlementService);
        this.interruptionFinalizer =
                Objects.requireNonNull(interruptionFinalizer);
        this.terminalBillingPolicy = Objects.requireNonNull(
                terminalBillingPolicy);
        this.failureDiagnosticService = Objects.requireNonNull(
                failureDiagnosticService);
        this.timingDiagnosticService = Objects.requireNonNull(
                timingDiagnosticService);
        this.lifecycleDiagnosticService = Objects.requireNonNull(
                lifecycleDiagnosticService);
        this.concurrencyService = Objects.requireNonNull(concurrencyService);
        this.leaseService = Objects.requireNonNull(leaseService);
        this.compactionService = Objects.requireNonNull(compactionService);
        this.streamingStrategies = Objects.requireNonNull(streamingStrategies);
        this.tokenizer = Objects.requireNonNull(tokenizer);
        this.idempotencyHasher = Objects.requireNonNull(idempotencyHasher);
        this.activeRegistry = Objects.requireNonNull(activeRegistry);
        this.directControlStore = Objects.requireNonNull(directControlStore);
        this.asyncGenerationProperties = Objects.requireNonNull(
                asyncGenerationProperties);
        this.webSearchProperties = Objects.requireNonNull(webSearchProperties);
        this.usageMapper = Objects.requireNonNull(usageMapper);
        this.hybridIdCodec = Objects.requireNonNull(hybridIdCodec);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.properties = Objects.requireNonNull(properties);
        this.metrics = Objects.requireNonNull(metrics);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.finalizerExecutor = Objects.requireNonNull(finalizerExecutor);
    }

    @Override
    public AiConversationResponseStream respond(
            AiConversationResponseCommand command) {
        Objects.requireNonNull(command);
        AiConversationLifecycleTraceContext traceContext =
                new AiConversationLifecycleTraceContext(
                        currentTraceId(),
                        currentClientRequestId(),
                        "unavailable",
                        "unavailable",
                        "unavailable",
                        currentRequestStartedNanos());
        lifecycleDiagnosticService.record(traceContext, "REQUEST_RECEIVED");
        try {
            validateCommand(command);
            List<AiConversationAttachment> attachments =
                    attachmentService.validateTemporaryInputs(
                            command.userPublicId(),
                            command.input().uploadReferences());
            AiConversationResponseCommand prepared =
                    new AiConversationResponseCommand(
                            command.userId(),
                            command.userPublicId(),
                            command.conversationId(),
                            command.modelPublicId(),
                            command.reasoningEffort(),
                            command.webSearchMode(),
                            command.imageGeneration(),
                            command.idempotencyKey(),
                            command.input().validated(attachments));
            return respondValidated(prepared, traceContext);
        } catch (RuntimeException failure) {
            lifecycleDiagnosticService.record(
                    traceContext,
                    "REQUEST_REJECTED",
                    AiConversationLifecycleEvent.terminal(
                            "FAILED",
                            "unavailable",
                            "unavailable",
                            diagnosticFailureCode(failure),
                            "unavailable",
                            "unavailable",
                            false,
                            false,
                            0L));
            throw failure;
        }
    }

    private AiConversationResponseStream respondValidated(
            AiConversationResponseCommand command,
            AiConversationLifecycleTraceContext requestTraceContext) {
        AiModelCacheEntry model = requiredModel(
                publicIdCodec.decode(command.modelPublicId()));
        AiModelProvider provider = AiModelProvider.fromVendor(model.vendor());
        provider.validateReasoningEffort(command.reasoningEffort());
        AiConversationStreamingProtocol protocol = command.webSearchMode()
                        == AiConversationWebSearchMode.OFF
                ? AiConversationStreamingProtocol.CHAT_COMPLETIONS
                : AiConversationStreamingProtocol.RESPONSES_WEB_SEARCH;
        // 供应商、协议和推理等级必须在预扣前完成验证，避免不支持的请求先占用用户额度。
        AiConversationStreamingStrategy streamingStrategy =
                streamingStrategies.getRequired(provider, protocol);
        validateProtocolCapabilities(model, command.webSearchMode());
        validateWebSearchEnabled(command.webSearchMode());
        AiConversationLifecycleTraceContext validatedTraceContext =
                requestTraceContext.withModelPublicId(command.modelPublicId());
        validateAttachmentCapabilities(model, command.input().attachments());
        validateExistingConversation(command);
        AiConversationPromptSnapshot preliminary =
                command.conversationId() == null
                        ? contextService.prepareNew(model, command.input())
                        : prepareExistingWithEmergencyCompaction(
                                command, model);
        AiConversationConcurrencyPermit concurrencyPermit = concurrencyService
                .tryAcquire(command.userId())
                .orElseThrow(() -> new AiConversationException(
                        AiConversationErrorCode.AI_CONCURRENCY_LIMIT_REACHED,
                        "当前模型调用并发已达到限制",
                        true));
        AiConversationReservation reservation;
        lifecycleDiagnosticService.record(
                validatedTraceContext, "RESERVATION_STARTED");
        try {
            if (streamingStrategy.meteringBasis()
                    != AiConversationMeteringBasis.TOKEN) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                        "当前同步会话链路不支持该模型计量方式",
                        false);
            }
            reservation = billingService.reserve(
                    new AiConversationReservationCommand(
                            command.userId(),
                            command.conversationId(),
                            model,
                            idempotencyHasher.digest(
                                    command.userId(), command.idempotencyKey()),
                            new TokenReservationMetering(
                                    preliminary.estimatedPromptTokens(),
                                    model.maxOutputTokens(),
                                    model.inputRatio(),
                                    model.cachedInputRatio(),
                                    model.outputRatio())));
        } catch (RuntimeException failure) {
            lifecycleDiagnosticService.record(
                    validatedTraceContext,
                    "RESERVATION_FAILED",
                    AiConversationLifecycleEvent.terminal(
                            "FAILED",
                            "unavailable",
                            "unavailable",
                            diagnosticFailureCode(failure),
                            "unavailable",
                            "unavailable",
                            false,
                            false,
                            0L));
            releaseConcurrency(concurrencyPermit);
            throw failure;
        }
        AiConversationRequestLifecycle lifecycle =
                new AiConversationRequestLifecycle();
        lifecycle.markReserved();
        String conversationPublicId =
                hybridIdCodec.encode(reservation.conversationId());
        String usagePublicId = hybridIdCodec.encode(reservation.usageId());
        AiConversationLifecycleTraceContext traceContext =
                validatedTraceContext.withBusinessCorrelation(
                        usagePublicId, conversationPublicId);
        lifecycleDiagnosticService.record(
                traceContext, "RESERVATION_COMPLETED");
        if (reservation.replay()) {
            lifecycleDiagnosticService.record(
                    traceContext,
                    "REQUEST_REPLAYED",
                    AiConversationLifecycleEvent.terminal(
                            "COMPLETED",
                            "ON_COMPLETE",
                            "REPLAY",
                            null,
                            "SETTLE_REPORTED_USAGE",
                            AiModelBillingStatus.SETTLED.name(),
                            true,
                            true,
                            0L));
            releaseConcurrency(concurrencyPermit);
            AiConversationResponseStream replayed = replay(
                    reservation,
                    conversationPublicId,
                    usagePublicId,
                    model);
            lifecycleDiagnosticService.record(
                    traceContext, "SSE_FIRST_EVENT_READY");
            return replayed;
        }

        AiConversationPromptSnapshot prompt;
        AiConversationEphemeralStart ephemeralStart;
        AiConversationLease lease = null;
        try {
            prompt = reservation.newConversation()
                    ? contextService.prepare(
                            reservation.conversationId(),
                            conversationPublicId,
                            model,
                            command.input())
                    : preliminary;
            lease = leaseService.tryAcquire(
                            conversationPublicId,
                            AiConversationLeaseType.INFLIGHT)
                    .orElseThrow(() -> new AiConversationException(
                            AiConversationErrorCode.AI_CONVERSATION_BUSY,
                            "当前会话已有模型响应正在生成",
                            true));
            ephemeralStart = contextStore.appendEphemeralUser(
                    conversationPublicId,
                    prompt.generation(),
                    usagePublicId,
                    command.input());
            if (ephemeralStart.outcome()
                    == AiConversationContextWriteOutcome.GENERATION_MISMATCH) {
                prompt = contextService.prepare(
                        reservation.conversationId(),
                        conversationPublicId,
                        model,
                        command.input());
                ephemeralStart = contextStore.appendEphemeralUser(
                        conversationPublicId,
                        prompt.generation(),
                        usagePublicId,
                        command.input());
            }
            if (ephemeralStart.outcome()
                    != AiConversationContextWriteOutcome.APPLIED) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_CONTEXT_CACHE_UNAVAILABLE,
                        "会话上下文缓存当前不可用",
                        true);
            }
        } catch (RuntimeException exception) {
            try {
                lifecycleDiagnosticService.withContext(
                        traceContext,
                        () -> settlementService.refundFailed(
                                reservation.usageId(),
                                "AI_CONTEXT_CACHE_UNAVAILABLE"));
            } finally {
                if (lease != null) {
                    try {
                        leaseService.release(lease);
                    } catch (RuntimeException ignoredFailure) {
                        // 会话租约由短 TTL 收敛。
                    }
                }
                releaseConcurrency(concurrencyPermit);
            }
            throw exception;
        }

        AiConversationStreamEvent accepted =
                AiConversationStreamEvent.accepted(
                        new AiConversationAcceptedData(
                                conversationPublicId,
                                usagePublicId,
                                publicIdCodec.encode(model.id()),
                                reservation.newConversation()));
        // accepted 是本次 POST SSE 的首个可发送事件，不能把后续首段 delta 误记为首事件准备完成。
        lifecycleDiagnosticService.record(
                traceContext, "SSE_FIRST_EVENT_READY");
        AiConversationLease activeLease = lease;
        AiConversationPromptSnapshot activePrompt = prompt;
        Flux<AiConversationStreamEvent> core = generation(
                command,
                model,
                provider,
                streamingStrategy,
                reservation,
                activePrompt,
                conversationPublicId,
                usagePublicId,
                activeLease,
                lifecycle,
                concurrencyPermit,
                ephemeralStart.ordinal(),
                prompt.generation(),
                traceContext);
        return new AiConversationResponseStream(accepted, core);
    }

    private Flux<AiConversationStreamEvent> generation(
            AiConversationResponseCommand command,
            AiModelCacheEntry model,
            AiModelProvider provider,
            AiConversationStreamingStrategy streamingStrategy,
            AiConversationReservation reservation,
            AiConversationPromptSnapshot prompt,
            String conversationPublicId,
            String usagePublicId,
            AiConversationLease lease,
            AiConversationRequestLifecycle lifecycle,
            AiConversationConcurrencyPermit concurrencyPermit,
            long ephemeralOrdinal,
            String cacheGeneration,
            AiConversationLifecycleTraceContext traceContext) {
        StreamState state = new StreamState(
                lifecycle,
                ephemeralOrdinal,
                cacheGeneration,
                command.input(),
                usagePublicId,
                command.modelPublicId(),
                traceContext);
        HmacIdentifier directRequestIdentifier = idempotencyHasher.identifier(
                command.userId(), command.idempotencyKey());
        state.directRequestIdentifier = directRequestIdentifier;
        AtomicReference<Subscription> responseSubscription =
                new AtomicReference<>();
        AiConversationDirectResponseCancellationHandle cancellationHandle =
                interruptionSource -> {
                    state.interruptionSource.set(interruptionSource);
                    Subscription subscription = responseSubscription.get();
                    if (subscription != null) {
                        subscription.cancel();
                    }
                };
        state.cancellationHandle = cancellationHandle;
        Flux<AiConversationModelEvent> upstream = Flux.defer(() -> {
                    // 先注册本机句柄和 Redis Owner，再订阅模型；显式 Stop 的意图会在下方订阅闸门再次检查。
                    activeRegistry.register(
                            directRequestIdentifier.value(), cancellationHandle);
                    directControlStore.registerOwner(
                            directRequestIdentifier,
                            asyncGenerationProperties.instanceId(),
                            asyncGenerationProperties.maxWorkerDuration()
                                    .plus(Duration.ofMinutes(1)));
                    if (directControlStore.userStopRequested(
                            directRequestIdentifier)) {
                        state.interruptionSource.set(
                                AiConversationInterruptionSource.USER_STOP);
                    }
                    lifecycle.markConnecting();
                    lifecycleDiagnosticService.record(
                            traceContext, "UPSTREAM_SUBSCRIBE_STARTED");
                    AiConversationStreamingRequest streamingRequest =
                            new AiConversationStreamingRequest(
                                    new AiConversationModelRequest(
                                            provider,
                                            model.modelName(),
                                            model.maxOutputTokens(),
                                            command.reasoningEffort(),
                                            prompt),
                                    command.webSearchMode());
                    return lifecycleDiagnosticService.withContext(
                            traceContext,
                            () -> streamingStrategy.stream(streamingRequest))
                            .doOnNext(ignored -> {
                                if (state.firstByteRecorded.compareAndSet(
                                        false, true)) {
                                    lifecycleDiagnosticService.record(
                                            traceContext,
                                            "UPSTREAM_FIRST_CHUNK");
                                    metrics.firstByte(Duration.ofNanos(
                                            System.nanoTime()
                                                    - state.startedNanos));
                                }
                            });
                })
                .doOnSubscribe(ignored -> lifecycleDiagnosticService.record(
                        traceContext, "UPSTREAM_SUBSCRIBED"))
                .doFinally(signal -> {
                    if (state.upstreamTerminalRecorded.compareAndSet(
                            false, true)) {
                        lifecycleDiagnosticService.record(
                                traceContext,
                                "UPSTREAM_TERMINAL",
                                AiConversationLifecycleEvent.terminal(
                                        upstreamOutcome(signal),
                                        signal.name(),
                                        state.finishReason.get(),
                                        null,
                                        "unavailable",
                                        "unavailable",
                                        state.answer.length() > 0,
                                        state.usage.get() != null,
                                        state.answer.length()));
                    }
                });
        Flux<AiConversationStreamEvent> streamed = timingDiagnosticService
                .observeBoundary(
                        upstream,
                        AiConversationStreamTimingBoundary.AFTER_STREAM_BATCHER,
                        AiConversationResponseServiceImpl
                                ::modelEventTextCharacters)
                .concatMapIterable(event -> processModelEvent(event, state));
        Flux<AiConversationStreamEvent> visible = Flux.concat(
                Mono.fromSupplier(() -> localActivity(
                        state,
                        "processing-local",
                        AiConversationActivityPhase.PROCESSING,
                        AiConversationActivityStatus.STARTED)),
                streamed);
        Flux<AiConversationStreamEvent> completed = visible.concatWith(
                Flux.concat(
                        Mono.defer(() -> finalizingEvent(state)),
                        Mono.defer(() -> successFinalization(
                                command,
                                reservation,
                                prompt,
                                conversationPublicId,
                                usagePublicId,
                                state,
                                lease,
                                concurrencyPermit))));
        // 心跳错误必须和模型流错误进入同一个终态策略；放在 onErrorResume 外会把租约故障误判成客户端取消。
        Flux<AiConversationStreamEvent> withHeartbeat = completed.publish(
                shared -> Flux.merge(
                        shared,
                        Flux.interval(HEARTBEAT_INTERVAL)
                                .takeUntilOther(shared.ignoreElements())
                                .map(ignored -> {
                                    try {
                                        boolean conversationRenewed =
                                                leaseService.renew(lease);
                                        boolean concurrencyRenewed =
                                                concurrencyService.renew(
                                                        concurrencyPermit);
                                        if (!conversationRenewed
                                                || !concurrencyRenewed) {
                                            throw new AiConversationException(
                                                    AiConversationErrorCode
                                                            .AI_CONTEXT_CACHE_UNAVAILABLE,
                                                    "模型调用并发租约已经失效",
                                                    true);
                                        }
                                    } catch (RuntimeException ignoredFailure) {
                                        throw new AiConversationException(
                                                AiConversationErrorCode
                                                        .AI_CONTEXT_CACHE_UNAVAILABLE,
                                                "模型调用并发租约续期失败",
                                                true);
                                    }
                                    return AiConversationStreamEvent.heartbeat();
                                })));
        Flux<AiConversationStreamEvent> terminalized = withHeartbeat
                .onErrorResume(failure -> finalizeFailure(
                        command,
                        reservation,
                        prompt,
                        conversationPublicId,
                        usagePublicId,
                        state,
                        lease,
                        concurrencyPermit,
                        failure))
                .doFinally(signal -> {
                    boolean releaseLease = true;
                    if (signal == SignalType.CANCEL) {
                        lifecycleDiagnosticService.record(
                                traceContext,
                                "REACTOR_CANCEL_OBSERVED",
                                AiConversationLifecycleEvent.terminal(
                                        "CANCELLED",
                                        signal.name(),
                                        state.finishReason.get(),
                                        null,
                                        "unavailable",
                                        lifecycle.state().name(),
                                        state.answer.length() > 0,
                                        state.usage.get() != null,
                                        state.answer.length()));
                        AiConversationRequestState stateBefore = lifecycle.state();
                        if (lifecycle.tryBeginInterruptedFinalization()) {
                            AiConversationInterruptionSource interruptionSource =
                                    resolvedInterruptionSource(state);
                            lifecycleDiagnosticService.record(
                                    traceContext,
                                    "TERMINAL_OWNERSHIP_CLAIMED",
                                    lifecycleStateEvent(
                                            stateBefore,
                                            lifecycle.state(),
                                            signal));
                            markInterruptedCacheBestEffort(
                                    conversationPublicId,
                                    state,
                                    interruptionSource);
                            AiConversationInterruptionCommand interruption =
                                    interruptionCommand(
                                            command, reservation, state);
                            lifecycleDiagnosticService.withContext(
                                    traceContext,
                                    () -> interruptionFinalizer.submit(
                                            interruption, lifecycle));
                        } else {
                            lifecycleDiagnosticService.record(
                                    traceContext,
                                    "TERMINAL_OWNERSHIP_REJECTED",
                                    lifecycleStateEvent(
                                            stateBefore,
                                            lifecycle.state(),
                                            signal));
                            if (lifecycle.state()
                                    == AiConversationRequestState
                                            .FINALIZING_SUCCESS) {
                                scheduleDetachedSuccessFinalization(
                                        command,
                                        reservation,
                                        prompt,
                                        conversationPublicId,
                                        usagePublicId,
                                        state,
                                        lease,
                                        concurrencyPermit);
                                // 成功任务负责释放；这里不能在数据库结算前提前开放同一会话和并发名额。
                                releaseLease = false;
                            }
                        }
                    }
                    if (releaseLease) {
                        releaseAfterGeneration(
                                lease, concurrencyPermit, state);
                    }
                    cleanupDirectResponseControl(state);
                });
        Flux<AiConversationStreamEvent> ready = timingDiagnosticService
                .observeBoundary(
                        terminalized,
                        AiConversationStreamTimingBoundary.SSE_EVENT_READY,
                        AiConversationResponseServiceImpl::eventTextCharacters);
        Flux<AiConversationStreamEvent> session = timingDiagnosticService.withSession(
                ready,
                new AiConversationStreamTimingContext(
                        state.traceId,
                        usagePublicId,
                        conversationPublicId,
                        command.modelPublicId(),
                        AiConversationStreamTimingPath.DIRECT_RESPONSE,
                        state.startedNanos));
        return session.doOnSubscribe(subscription -> {
            responseSubscription.set(subscription);
            // 取消整条响应订阅才能触发外层 doFinally(CANCEL)，不能只截断模型上游后留下永不终止的 SSE。
            if (state.interruptionSource.get()
                            == AiConversationInterruptionSource.USER_STOP
                    || userStopRequestedBestEffort(directRequestIdentifier)) {
                state.interruptionSource.set(
                        AiConversationInterruptionSource.USER_STOP);
                subscription.cancel();
            }
        });
    }

    private static int eventTextCharacters(AiConversationStreamEvent event) {
        if (!"delta".equals(event.name())
                || !(event.data() instanceof AiConversationDeltaData delta)
                || !"TEXT".equals(delta.type())
                || delta.text() == null) {
            return 0;
        }
        return delta.text().length();
    }

    private static int modelEventTextCharacters(
            AiConversationModelEvent event) {
        if (event instanceof AiConversationModelEvent.Chunk chunk) {
            return chunk.value().text().length();
        }
        if (event instanceof AiConversationModelEvent.ReasoningSummaryDelta
                summary) {
            return summary.textDelta().length();
        }
        return 0;
    }

    private List<AiConversationStreamEvent> processModelEvent(
            AiConversationModelEvent event,
            StreamState state) {
        if (event instanceof AiConversationModelEvent.Chunk chunk) {
            return processChunk(chunk.value(), state);
        }
        if (event instanceof AiConversationModelEvent.Activity activity) {
            String eventId = state.researchActivityEvents.accept(activity);
            if (eventId == null) {
                return List.of();
            }
            return List.of(AiConversationStreamEvent.activity(
                    new AiConversationActivityData(
                            state.sequence.incrementAndGet(),
                            eventId,
                            activity.activityId(),
                            activity.phase().name(),
                            activity.status().name(),
                            activity.query(),
                            occurredAt())));
        }
        if (event instanceof AiConversationModelEvent.Source source) {
            String sourceKey = source.role().name() + "\n" + source.url();
            if (state.researchSourceKeys.size() >= MAX_RESEARCH_SOURCES
                    || !state.researchSourceKeys.add(sourceKey)) {
                return List.of();
            }
            return List.of(AiConversationStreamEvent.source(
                    new AiConversationSourceData(
                            state.sequence.incrementAndGet(),
                            source.activityId(),
                            source.sourceId(),
                            source.title(),
                            source.url(),
                            source.domain(),
                            source.role().name(),
                            occurredAt())));
        }
        if (event instanceof AiConversationModelEvent.ReasoningSummaryDelta
                summary) {
            List<AiConversationStreamEvent> events = new ArrayList<>(2);
            if (state.reasoningStarted.compareAndSet(false, true)) {
                events.add(AiConversationStreamEvent.activity(
                        new AiConversationActivityData(
                                state.sequence.incrementAndGet(),
                                AiConversationActivityEventDeduplicator.eventId(
                                        summary.activityId(),
                                        AiConversationActivityPhase.REASONING,
                                        AiConversationActivityStatus.STARTED,
                                        null),
                                summary.activityId(),
                                AiConversationActivityPhase.REASONING.name(),
                                AiConversationActivityStatus.STARTED.name(),
                                null,
                                occurredAt())));
            }
            events.add(AiConversationStreamEvent.reasoningSummary(
                    new AiConversationReasoningSummaryData(
                            state.sequence.incrementAndGet(),
                            summary.activityId(),
                            summary.textDelta(),
                            occurredAt())));
            return List.copyOf(events);
        }
        if (event instanceof AiConversationModelEvent.Failure) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                    "模型响应未能完成",
                    true,
                    AiConversationStreamFailureReason.UPSTREAM_PROTOCOL_ERROR,
                    null);
        }
        return List.of();
    }

    private static AiConversationStreamEvent localActivity(
            StreamState state,
            String activityId,
            AiConversationActivityPhase phase,
            AiConversationActivityStatus status) {
        return AiConversationStreamEvent.activity(
                new AiConversationActivityData(
                        state.sequence.incrementAndGet(),
                        AiConversationActivityEventDeduplicator.eventId(
                                activityId, phase, status, null),
                        activityId,
                        phase.name(),
                        status.name(),
                        null,
                        occurredAt()));
    }

    private static String occurredAt() {
        return Instant.now().toString();
    }

    private List<AiConversationStreamEvent> processChunk(
            AiConversationModelChunk chunk,
            StreamState state) {
        List<AiConversationStreamEvent> events = new ArrayList<>();
        state.lifecycle.markStreaming();
        boolean terminalObservedBefore = state.terminalFinishObserved.get();
        boolean terminalInCurrentChunk = chunk.finishReason() != null
                && !chunk.finishReason().isBlank();
        if (chunk.usage() != null) {
            state.candidateUsage.set(chunk.usage());
            // 只接受与终止片同片或在终止片之后到达的 Usage，避免提升更早的中间累计值。
            if (terminalObservedBefore || terminalInCurrentChunk) {
                state.usage.set(chunk.usage());
            }
        }
        if (chunk.upstreamRequestId() != null) {
            state.upstreamRequestId.set(chunk.upstreamRequestId());
        }
        if (terminalInCurrentChunk) {
            state.finishReason.set(chunk.finishReason());
            state.terminalFinishObserved.set(true);
        }
        if (!chunk.generatedMedia().isEmpty()) {
            for (AiConversationGeneratedMedia media : chunk.generatedMedia()) {
                String fingerprint = generatedMediaFingerprint(media);
                if (state.generatedMediaFingerprints.contains(fingerprint)) {
                    continue;
                }
                long mediaBytes = media.bytes().length;
                if (state.generatedMedia.size()
                                >= attachmentProperties.maxFilesPerMessage()
                        || mediaBytes
                                > attachmentProperties.maxTotalBytesPerMessage()
                                        - state.generatedMediaBytes) {
                    state.generatedMediaRejected = true;
                    continue;
                }
                state.generatedMediaFingerprints.add(fingerprint);
                state.generatedMedia.add(media);
                state.generatedMediaBytes += mediaBytes;
            }
        }
        if (chunk.generatedMediaTruncated()) {
            // 上游单片已经超过媒体边界时只保留受限部分，并在完成事件中向客户端明确告警。
            state.generatedMediaRejected = true;
        }
        if (!chunk.text().isEmpty()) {
            appendBounded(state, chunk.text());
            if (state.generatingStarted.compareAndSet(false, true)) {
                events.add(localActivity(
                        state,
                        "generating-local",
                        AiConversationActivityPhase.GENERATING,
                        AiConversationActivityStatus.STARTED));
            }
            events.add(AiConversationStreamEvent.delta(
                    new AiConversationDeltaData(
                            state.sequence.incrementAndGet(),
                            "TEXT",
                            chunk.text())));
        }
        if (state.usage.get() != null) {
            // 最终 Usage 到达即抢占成功终态，使随后发生的浏览器断开不能改走取消退款。
            state.lifecycle.tryBeginSuccessFinalization();
        }
        return events;
    }

    private Mono<AiConversationStreamEvent> finalizingEvent(
            StreamState state) {
        if (state.usage.get() == null) {
            return Mono.empty();
        }
        return Mono.just(localActivity(
                state,
                "finalizing-local",
                AiConversationActivityPhase.FINALIZING,
                AiConversationActivityStatus.STARTED));
    }

    private AiConversationStreamEvent complete(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            AiConversationPromptSnapshot prompt,
            String conversationPublicId,
            String usagePublicId,
            StreamState state) {
        AiConversationUsage usage = state.usage.get();
        if (usage == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_USAGE_UNAVAILABLE,
                    "模型没有返回可靠的最终用量",
                    false);
        }
        AiConversationRequestState stateBefore = state.lifecycle.state();
        if (stateBefore != AiConversationRequestState.FINALIZING_SUCCESS) {
            if (!state.lifecycle.tryBeginSuccessFinalization()) {
                lifecycleDiagnosticService.record(
                        state.traceContext,
                        "TERMINAL_OWNERSHIP_REJECTED",
                        lifecycleStateEvent(
                                stateBefore,
                                state.lifecycle.state(),
                                SignalType.ON_COMPLETE));
                throw new AiConversationException(
                        AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                        "本次请求已经进入中断结算",
                        false);
            }
            lifecycleDiagnosticService.record(
                    state.traceContext,
                    "TERMINAL_OWNERSHIP_CLAIMED",
                    lifecycleStateEvent(
                            stateBefore,
                            state.lifecycle.state(),
                            SignalType.ON_COMPLETE));
        }
        lifecycleDiagnosticService.record(
                state.traceContext,
                "TERMINAL_POLICY_DECIDED",
                AiConversationLifecycleEvent.terminal(
                        "COMPLETED",
                        "ON_COMPLETE",
                        Objects.requireNonNullElse(
                                state.finishReason.get(), "STOP"),
                        null,
                        AiConversationTerminalBillingAction
                                .SETTLE_REPORTED_USAGE.name(),
                        "unavailable",
                        state.answer.length() > 0,
                        true,
                        state.answer.length()));
        long messageId = settlementService.reserveMessageId();
        String messagePublicId = publicIdCodec.encode(messageId);
        AiConversationAttachmentFinalization attachmentFinalization =
                attachmentService.finalizeAttachments(
                        command.userPublicId(),
                        conversationPublicId,
                        messagePublicId,
                        command.input().attachments(),
                        state.generatedMedia);
        AiConversationContent finalizedUser = new AiConversationContent(
                command.input().text(),
                attachmentFinalization.inputAttachments());
        AiConversationContent assistant = new AiConversationContent(
                state.answer.toString(),
                attachmentFinalization.responseAttachments());
        AiConversationSettlementResult settlement;
        try {
            settlement = lifecycleDiagnosticService.withContext(
                    state.traceContext,
                    () -> settlementService.complete(
                            settlementCommand(
                                    command,
                                    reservation,
                                    messageId,
                                    finalizedUser,
                                    assistant,
                                    state,
                                    usage)));
        } catch (RuntimeException failure) {
            // OSS 与 PostgreSQL 不具备原子事务；数据库失败时只执行尽力删除，清理失败交由指标暴露。
            attachmentService.compensateCreatedObjects(
                    attachmentFinalization.createdObjectKeys());
            throw failure;
        }
        if (settlement.requiresReconciliation()) {
            state.lifecycle.markReconcileRequired();
            recordRequestOutcome(state, "reconcile");
        } else {
            state.lifecycle.markSettled();
            recordRequestOutcome(state, "settled");
        }
        lifecycleDiagnosticService.record(
                state.traceContext,
                "FINALIZER_COMPLETED",
                AiConversationLifecycleEvent.terminal(
                        "COMPLETED",
                        "ON_COMPLETE",
                        Objects.requireNonNullElse(
                                state.finishReason.get(), "STOP"),
                        settlement.requiresReconciliation()
                                ? "AI_SETTLEMENT_RECONCILE_REQUIRED" : null,
                        AiConversationTerminalBillingAction
                                .SETTLE_REPORTED_USAGE.name(),
                        state.lifecycle.state().name(),
                        state.answer.length() > 0,
                        true,
                        state.answer.length()));
        metrics.stream(Duration.ofNanos(
                System.nanoTime() - state.startedNanos));
        // PostgreSQL 事务已经提交，此处只尽力合并缓存；持续失败时清除落后快照供下次按数据库重建。
        commitPersistedCache(
                conversationPublicId,
                settlement.messageId(),
                finalizedUser,
                assistant,
                state);
        if (prompt.shouldCompactAfterCompletion()) {
            try {
                compactionService.schedule(
                        reservation.conversationId(),
                        conversationPublicId,
                        state.cacheGeneration.get(),
                        settlement.messageId());
            } catch (RuntimeException ignoredFailure) {
                // 压缩是可重建派生数据，调度失败不能覆盖已经提交的消息和结算。
            }
        }
        return AiConversationStreamEvent.completed(
                new AiConversationCompletedData(
                        conversationPublicId,
                        messagePublicId,
                        usagePublicId,
                        Long.toString(usage.promptTokens()),
                        Long.toString(usage.cachedPromptTokens()),
                        Long.toString(usage.completionTokens()),
                        Long.toString(usage.reasoningTokens()),
                        Long.toString(settlement.chargedQuotaMinor()),
                        Objects.requireNonNullElse(
                                state.finishReason.get(), "STOP"),
                        attachmentFinalization.inputAttachments(),
                        attachmentFinalization.responseAttachments(),
                        attachmentFinalization.partialFailure()
                                        || state.generatedMediaRejected
                                ? List.of("ATTACHMENT_STORAGE_PARTIAL")
                                : List.of(),
                        state.sequence.incrementAndGet()));
    }

    private void commitPersistedCache(
            String conversationPublicId,
            long messageId,
            AiConversationContent user,
            AiConversationContent assistant,
            StreamState state) {
        AiConversationContextWriteOutcome outcome =
                AiConversationContextWriteOutcome.UNAVAILABLE;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                outcome = contextStore.commitPersistedTurn(
                        conversationPublicId,
                        state.cacheGeneration.get(),
                        messageId,
                        state.ephemeralOrdinal,
                        user,
                        assistant);
                if (outcome == AiConversationContextWriteOutcome.APPLIED
                        || outcome == AiConversationContextWriteOutcome.UNAVAILABLE) {
                    break;
                }
                AiConversationContextSnapshot current = contextStore
                        .find(conversationPublicId)
                        .orElse(null);
                if (current == null) {
                    outcome = AiConversationContextWriteOutcome.UNAVAILABLE;
                    break;
                }
                state.cacheGeneration.set(current.generation());
            }
        } catch (RuntimeException ignoredFailure) {
            outcome = AiConversationContextWriteOutcome.UNAVAILABLE;
        }
        if (outcome == AiConversationContextWriteOutcome.APPLIED) {
            return;
        }
        metrics.context(outcome
                == AiConversationContextWriteOutcome.GENERATION_MISMATCH
                        ? "generation_conflict" : "unavailable");
        try {
            contextStore.invalidate(conversationPublicId);
        } catch (RuntimeException ignoredFailure) {
            // Redis 故障时无法主动清理，只能依赖三天绝对 TTL；不能反向改写已提交的数据库结果。
        }
    }

    private Mono<AiConversationStreamEvent> finalizeFailure(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            AiConversationPromptSnapshot prompt,
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            AiConversationLease lease,
            AiConversationConcurrencyPermit concurrencyPermit,
            Throwable failure) {
        if (state.lifecycle.state()
                == AiConversationRequestState.FINALIZING_SUCCESS) {
            // 最终 Usage 已经取得成功所有权时，上游尾部异常不能把已完成调用降级为中断结算。
            return successFinalization(
                    command,
                    reservation,
                    prompt,
                    conversationPublicId,
                    usagePublicId,
                    state,
                    lease,
                    concurrencyPermit);
        }
        AiConversationRequestState stateBefore = state.lifecycle.state();
        if (!state.lifecycle.tryBeginInterruptedFinalization()) {
            lifecycleDiagnosticService.record(
                    state.traceContext,
                    "TERMINAL_OWNERSHIP_REJECTED",
                    lifecycleStateEvent(
                            stateBefore,
                            state.lifecycle.state(),
                            SignalType.ON_ERROR));
            return Mono.just(buildTerminalErrorEvent(
                    conversationPublicId,
                    usagePublicId,
                    state,
                    failure,
                    failure));
        }
        lifecycleDiagnosticService.record(
                state.traceContext,
                "TERMINAL_OWNERSHIP_CLAIMED",
                lifecycleStateEvent(
                        stateBefore,
                        state.lifecycle.state(),
                        SignalType.ON_ERROR));
        long submittedNanos = System.nanoTime();
        lifecycleDiagnosticService.record(
                state.traceContext, "FINALIZER_SUBMITTED");
        try {
            CompletableFuture<AiConversationStreamEvent> future =
                    CompletableFuture.supplyAsync(
                            () -> lifecycleDiagnosticService.withContext(
                                    state.traceContext,
                                    () -> {
                                        lifecycleDiagnosticService.record(
                                                state.traceContext,
                                                "FINALIZER_STARTED",
                                                AiConversationLifecycleEvent
                                                        .execution(
                                                                null,
                                                                elapsedMillis(
                                                                        submittedNanos)));
                                        return failAfterSse(
                                                reservation,
                                                conversationPublicId,
                                                usagePublicId,
                                                state,
                                                failure);
                                    }),
                            finalizerExecutor);
            // 浏览器随后断开时仍要让已经取得中断所有权的有限结算任务完成。
            return Mono.fromFuture(future, true);
        } catch (RejectedExecutionException rejected) {
            // 中断结算队列已满时直接执行有限短事务；系统失败不能因为本地队列饱和而吞掉退款。
            lifecycleDiagnosticService.record(
                    state.traceContext,
                    "FINALIZER_REJECTED_SYNC_FALLBACK",
                    AiConversationLifecycleEvent.execution(
                            null, elapsedMillis(submittedNanos)));
            return Mono.fromCallable(() -> lifecycleDiagnosticService.withContext(
                    state.traceContext,
                    () -> failAfterSse(
                            reservation,
                            conversationPublicId,
                            usagePublicId,
                            state,
                            failure)));
        }
    }

    private AiConversationStreamEvent failAfterSse(
            AiConversationReservation reservation,
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            Throwable failure) {
        AiConversationInterruptionCommand interruption =
                interruptionCommandForFailure(reservation, state, failure);
        lifecycleDiagnosticService.withContext(
                state.traceContext,
                () -> finalizeInterruptedSynchronously(
                        interruption, state.lifecycle));
        // Redis 草稿只是可恢复派生数据，失败不能阻止已经取得所有权的额度退款事务。
        markInterruptedCacheBestEffort(
                conversationPublicId,
                state,
                AiConversationInterruptionSource.SYSTEM_FAILURE);
        AiConversationRequestState terminalState = state.lifecycle.state();
        String outcome = switch (terminalState) {
            case RECONCILE_REQUIRED -> "reconcile";
            case FAILED_REFUNDED -> "failed";
            default -> "interrupted";
        };
        recordRequestOutcome(state, outcome);
        metrics.stream(Duration.ofNanos(
                System.nanoTime() - state.startedNanos));
        if (terminalState == AiConversationRequestState.RECONCILE_REQUIRED) {
            return buildTerminalErrorEvent(
                    conversationPublicId,
                    usagePublicId,
                    state,
                    new AiConversationException(
                            AiConversationErrorCode
                                    .AI_SETTLEMENT_RECONCILE_REQUIRED,
                            "模型请求已终止，额度状态正在核对",
                            false),
                    failure);
        }
        return buildTerminalErrorEvent(
                conversationPublicId,
                usagePublicId,
                state,
                failure,
                failure);
    }

    private AiConversationStreamEvent buildTerminalErrorEvent(
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            Throwable publicFailure,
            Throwable diagnosticFailure) {
        AiConversationException safe = publicFailure
                instanceof AiConversationException exception
                ? exception
                : new AiConversationException(
                        AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                        "模型响应未能完成",
                        true,
                        publicFailure);
        AiConversationStreamFailureClassification classification =
                diagnoseOnce(
                        conversationPublicId,
                        usagePublicId,
                        state,
                        safe,
                        diagnosticFailure);
        return AiConversationStreamEvent.error(
                new AiConversationErrorData(
                        safe.code().name(),
                        classification.reason().name(),
                        safe.retryable(),
                        usagePublicId,
                        publicErrorMessage(safe, classification.reason()),
                        state.sequence.incrementAndGet()));
    }

    private AiConversationStreamFailureClassification diagnoseOnce(
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            AiConversationException publicFailure,
            Throwable diagnosticFailure) {
        AiConversationStreamFailureClassification existing =
                state.failureClassification.get();
        if (existing != null) {
            return existing;
        }
        if (!state.diagnosticRecorded.compareAndSet(false, true)) {
            return fallbackClassification(publicFailure, diagnosticFailure);
        }
        AiConversationRequestState billingState = state.lifecycle.state();
        String refundOutcome = switch (billingState) {
            case FAILED_REFUNDED -> "refunded";
            case RECONCILE_REQUIRED -> "reconcile";
            default -> "interrupted";
        };
        AiConversationStreamFailureContext context =
                new AiConversationStreamFailureContext(
                        state.traceId,
                        usagePublicId,
                        conversationPublicId,
                        state.modelPublicId,
                        publicFailure.code().name(),
                        publicFailure.retryable(),
                        state.sequence.get(),
                        state.answer.length(),
                        TimeUnit.NANOSECONDS.toMillis(Math.max(
                                0L,
                                System.nanoTime() - state.startedNanos)),
                        billingState.name(),
                        refundOutcome);
        try {
            AiConversationStreamFailureClassification classified =
                    failureDiagnosticService.diagnose(
                            context, diagnosticFailure);
            state.failureClassification.compareAndSet(null, classified);
            return classified;
        } catch (RuntimeException diagnosticError) {
            // 诊断失败不能覆盖已经完成的退款终态，也不能阻止 SSE 向客户端发送受控错误。
            AiConversationStreamFailureClassification fallback =
                    fallbackClassification(publicFailure, diagnosticFailure);
            state.failureClassification.compareAndSet(null, fallback);
            return fallback;
        }
    }

    private static AiConversationStreamFailureClassification
            fallbackClassification(
                    AiConversationException publicFailure,
                    Throwable diagnosticFailure) {
        AiConversationStreamFailureReason reason = publicFailure.reason();
        if (reason == null
                && diagnosticFailure
                        instanceof AiConversationException controlled) {
            reason = controlled.reason();
        }
        if (reason == null) {
            reason = AiConversationStreamFailureReason.UNKNOWN_STREAM_FAILURE;
        }
        Throwable safeFailure = diagnosticFailure == null
                ? publicFailure
                : diagnosticFailure;
        String type = safeFailure.getClass().getName();
        return new AiConversationStreamFailureClassification(
                reason,
                0,
                type,
                type,
                "unavailable",
                "unavailable");
    }

    private static String publicErrorMessage(
            AiConversationException failure,
            AiConversationStreamFailureReason reason) {
        if (failure.code()
                == AiConversationErrorCode.AI_SETTLEMENT_RECONCILE_REQUIRED) {
            return failure.getMessage();
        }
        if (failure.code() == AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED
                || failure.code() == AiConversationErrorCode.AI_UPSTREAM_TIMEOUT
                || failure.code()
                        == AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE
                || failure.code() == AiConversationErrorCode.AI_USAGE_UNAVAILABLE) {
            return reason.known()
                    ? "模型响应未能完成：" + reason.publicDetail()
                    : "模型响应未能完成";
        }
        return failure.getMessage();
    }

    private void markInterruptedCache(
            String conversationPublicId,
            StreamState state,
            AiConversationInterruptionSource interruptionSource) {
        List<String> completeAnswerChunks = utf8Chunks(
                state.answer.toString(), properties.streamFlushBytes());
        // 正文和中断来源必须由一个 Lua 原子操作提交，不能留下只有半份草稿的可见状态。
        AiConversationContextWriteOutcome outcome = contextStore.saveInterruptedTurn(
                        conversationPublicId,
                        state.cacheGeneration.get(),
                        state.ephemeralOrdinal,
                        completeAnswerChunks,
                        interruptionSource);
        if (outcome == AiConversationContextWriteOutcome.GENERATION_MISMATCH) {
            // 压缩或并发重建可能刚好切换 generation；在胜出快照重新建立临时轮次后再完整提交。
            AiConversationContextWriteOutcome restored =
                    restartEphemeralAfterGenerationMismatch(
                            conversationPublicId, state);
            if (restored == AiConversationContextWriteOutcome.APPLIED) {
                outcome = contextStore.saveInterruptedTurn(
                        conversationPublicId,
                        state.cacheGeneration.get(),
                        state.ephemeralOrdinal,
                        completeAnswerChunks,
                        interruptionSource);
            } else {
                outcome = restored;
            }
        }
        if (outcome == AiConversationContextWriteOutcome.APPLIED
                && interruptionSource == AiConversationInterruptionSource.USER_STOP) {
            compactionService.scheduleEphemeral(
                    conversationPublicId, state.cacheGeneration.get());
        }
    }

    private void markInterruptedCacheBestEffort(
            String conversationPublicId,
            StreamState state,
            AiConversationInterruptionSource interruptionSource) {
        try {
            markInterruptedCache(
                    conversationPublicId, state, interruptionSource);
        } catch (RuntimeException ignoredFailure) {
            // PostgreSQL 计费终态优先于 Redis 草稿；缓存失败由绝对 TTL 和历史重建收敛。
        }
    }

    private AiConversationInterruptionSource resolvedInterruptionSource(
            StreamState state) {
        AiConversationInterruptionSource source =
                state.interruptionSource.get();
        if (source == AiConversationInterruptionSource.TRANSPORT_DISCONNECT
                && state.directRequestIdentifier != null) {
            try {
                if (directControlStore.userStopRequested(
                        state.directRequestIdentifier)) {
                    source = AiConversationInterruptionSource.USER_STOP;
                    state.interruptionSource.set(source);
                }
            } catch (RuntimeException ignoredFailure) {
                // Redis 控制键不可读时保守归类为传输断开，避免无凭据地把技术中断纳入上下文。
            }
        }
        return source;
    }

    private boolean userStopRequestedBestEffort(
            HmacIdentifier requestIdentifier) {
        try {
            return directControlStore.userStopRequested(requestIdentifier);
        } catch (RuntimeException ignoredFailure) {
            // Redis 控制键不可读时继续建立响应；后续 Rabbit 控制或浏览器断开仍会有限收敛。
            return false;
        }
    }

    private AiConversationContextWriteOutcome restartEphemeralAfterGenerationMismatch(
            String conversationPublicId,
            StreamState state) {
        return contextStore.find(conversationPublicId)
                .map(snapshot -> {
                    AiConversationEphemeralStart restarted =
                            contextStore.appendEphemeralUser(
                                    conversationPublicId,
                                    snapshot.generation(),
                                    state.usagePublicId,
                                    state.input);
                    if (restarted.outcome()
                            != AiConversationContextWriteOutcome.APPLIED) {
                        return restarted.outcome();
                    }
                    state.cacheGeneration.set(snapshot.generation());
                    state.ephemeralOrdinal = restarted.ordinal();
                    return AiConversationContextWriteOutcome.APPLIED;
                })
                .orElse(AiConversationContextWriteOutcome.UNAVAILABLE);
    }

    private Mono<AiConversationStreamEvent> successFinalization(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            AiConversationPromptSnapshot prompt,
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            AiConversationLease lease,
            AiConversationConcurrencyPermit concurrencyPermit) {
        if (!state.successWorkStarted.compareAndSet(false, true)) {
            return Mono.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                    "本次请求的成功结算已经启动",
                    false));
        }
        long submittedNanos = System.nanoTime();
        lifecycleDiagnosticService.record(
                state.traceContext, "FINALIZER_SUBMITTED");
        try {
            CompletableFuture<AiConversationStreamEvent> future =
                    CompletableFuture.supplyAsync(
                            () -> lifecycleDiagnosticService.withContext(
                                    state.traceContext,
                                    () -> {
                                        lifecycleDiagnosticService.record(
                                                state.traceContext,
                                                "FINALIZER_STARTED",
                                                AiConversationLifecycleEvent
                                                        .execution(
                                                                null,
                                                                elapsedMillis(
                                                                        submittedNanos)));
                                        return completeAndRelease(
                                                command,
                                                reservation,
                                                prompt,
                                                conversationPublicId,
                                                usagePublicId,
                                                state,
                                                lease,
                                                concurrencyPermit);
                                    }),
                            finalizerExecutor);
            // suppressCancel=true：浏览器断开只能停止 SSE，不能取消已开始的最终 Usage 结算。
            return Mono.fromFuture(future, true);
        } catch (RejectedExecutionException rejected) {
            // 成功结算队列饱和时在当前受控调用栈完成一次终态事务，避免最终 Usage 已到达却永久遗留预扣。
            lifecycleDiagnosticService.record(
                    state.traceContext,
                    "FINALIZER_REJECTED_SYNC_FALLBACK",
                    AiConversationLifecycleEvent.execution(
                            null, elapsedMillis(submittedNanos)));
            return Mono.fromCallable(() -> lifecycleDiagnosticService.withContext(
                    state.traceContext,
                    () -> completeAndRelease(
                            command,
                            reservation,
                            prompt,
                            conversationPublicId,
                            usagePublicId,
                            state,
                            lease,
                            concurrencyPermit)));
        }
    }

    private void scheduleDetachedSuccessFinalization(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            AiConversationPromptSnapshot prompt,
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            AiConversationLease lease,
            AiConversationConcurrencyPermit concurrencyPermit) {
        if (!state.successWorkStarted.compareAndSet(false, true)) {
            return;
        }
        long submittedNanos = System.nanoTime();
        lifecycleDiagnosticService.record(
                state.traceContext, "FINALIZER_SUBMITTED");
        try {
            finalizerExecutor.execute(() -> lifecycleDiagnosticService.withContext(
                    state.traceContext,
                    () -> {
                        lifecycleDiagnosticService.record(
                                state.traceContext,
                                "FINALIZER_STARTED",
                                AiConversationLifecycleEvent.execution(
                                        null,
                                        elapsedMillis(submittedNanos)));
                        completeAndRelease(
                                command,
                                reservation,
                                prompt,
                                conversationPublicId,
                                usagePublicId,
                                state,
                                lease,
                                concurrencyPermit);
                    }));
        } catch (RejectedExecutionException rejected) {
            lifecycleDiagnosticService.record(
                    state.traceContext,
                    "FINALIZER_REJECTED_SYNC_FALLBACK",
                    AiConversationLifecycleEvent.execution(
                            null, elapsedMillis(submittedNanos)));
            state.successWorkStarted.set(false);
            try {
                lifecycleDiagnosticService.withContext(
                        state.traceContext,
                        () -> settlementService.markReconcileRequired(
                                reservation.usageId(),
                                "AI_SUCCESS_FINALIZER_QUEUE_FULL"));
            } catch (RuntimeException ignoredFailure) {
                // 数据库不可用时保持 RESERVED，由过期扫描在安全窗口后转待对账。
            }
            state.lifecycle.markReconcileRequired();
            recordRequestOutcome(state, "reconcile");
            releaseAfterGeneration(lease, concurrencyPermit, state);
        }
    }

    private AiConversationStreamEvent completeAndRelease(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            AiConversationPromptSnapshot prompt,
            String conversationPublicId,
            String usagePublicId,
            StreamState state,
            AiConversationLease lease,
            AiConversationConcurrencyPermit concurrencyPermit) {
        try {
            return complete(
                    command,
                    reservation,
                    prompt,
                    conversationPublicId,
                    usagePublicId,
                    state);
        } catch (RuntimeException failure) {
            if (state.lifecycle.state()
                    == AiConversationRequestState.FINALIZING_SUCCESS) {
                try {
                    settlementService.markReconcileRequired(
                            reservation.usageId(),
                            "AI_SUCCESS_FINALIZATION_FAILED");
                } catch (RuntimeException ignoredFailure) {
                    // 数据库持续不可用时由 RESERVED 过期扫描兜底。
                }
                state.lifecycle.markReconcileRequired();
                recordRequestOutcome(state, "reconcile");
            }
            throw failure;
        } finally {
            releaseAfterGeneration(lease, concurrencyPermit, state);
        }
    }

    private void releaseAfterGeneration(
            AiConversationLease lease,
            AiConversationConcurrencyPermit concurrencyPermit,
            StreamState state) {
        if (!state.generationResourcesReleased.compareAndSet(false, true)) {
            return;
        }
        // 成功任务、异常任务和 Reactor doFinally 可能相邻到达；CAS 保证租约与并发许可只释放一次。
        try {
            leaseService.release(lease);
        } catch (RuntimeException ignoredFailure) {
            // 会话租约释放失败由绝对 TTL 收敛。
        }
        releaseConcurrency(concurrencyPermit);
    }

    private void cleanupDirectResponseControl(StreamState state) {
        if (state.directRequestIdentifier == null) {
            return;
        }
        try {
            activeRegistry.remove(
                    state.directRequestIdentifier.value(),
                    state.cancellationHandle);
        } catch (RuntimeException ignoredFailure) {
            // 本机注册表只影响取消路由，流终态已由生命周期 CAS 决定。
        }
        try {
            directControlStore.clearOwner(
                    state.directRequestIdentifier,
                    asyncGenerationProperties.instanceId());
            directControlStore.clearUserStop(state.directRequestIdentifier);
        } catch (RuntimeException ignoredFailure) {
            // Redis 控制键由短 TTL 兜底，不能覆盖已经完成的计费和会话释放。
        }
    }

    private void recordRequestOutcome(StreamState state, String outcome) {
        if (state.requestOutcomeRecorded.compareAndSet(false, true)) {
            metrics.request(outcome);
        }
    }

    private AiConversationInterruptionCommand interruptionCommand(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            StreamState state) {
        AiConversationTerminalBillingDecision decision =
                terminalBillingPolicy.clientCancellation(
                        reservation,
                        state.usage.get(),
                        state.answer.toString());
        AiConversationSettlementCommand settlement = decision.usage() == null
                ? null
                : interruptionSettlementCommand(
                        command, reservation, state, decision);
        recordTerminalPolicyDecision(
                state,
                "CANCELLED",
                "CANCEL",
                decision);
        return new AiConversationInterruptionCommand(
                reservation.usageId(),
                settlement,
                decision.action(),
                decision.failureCode(),
                state.traceContext);
    }

    private AiConversationInterruptionCommand interruptionCommandForFailure(
            AiConversationReservation reservation,
            StreamState state,
            Throwable failure) {
        AiConversationTerminalBillingDecision decision =
                terminalBillingPolicy.systemFailure(failure);
        recordTerminalPolicyDecision(
                state,
                "FAILED",
                "ON_ERROR",
                decision);
        return new AiConversationInterruptionCommand(
                reservation.usageId(),
                null,
                decision.action(),
                decision.failureCode(),
                state.traceContext);
    }

    private void finalizeInterruptedSynchronously(
            AiConversationInterruptionCommand command,
            AiConversationRequestLifecycle lifecycle) {
        for (int attempt = 1;
                attempt <= MAX_INTERRUPTION_FINALIZATION_ATTEMPTS;
                attempt++) {
            if (attempt > 1) {
                lifecycleDiagnosticService.record(
                        command.traceContext(),
                        "SETTLEMENT_RETRY_STARTED",
                        AiConversationLifecycleEvent.execution(attempt, null));
            }
            try {
                if (command.action()
                        == AiConversationTerminalBillingAction
                                .SETTLE_REPORTED_USAGE
                        || command.action()
                        == AiConversationTerminalBillingAction
                                .SETTLE_ESTIMATED_CLIENT_CANCEL) {
                    AiConversationSettlementResult result =
                            settlementService.settleInterrupted(command.settlement());
                    if (result.requiresReconciliation()) {
                        lifecycle.markReconcileRequired();
                    } else {
                        lifecycle.markSettled();
                    }
                } else if (command.action()
                        == AiConversationTerminalBillingAction.REFUND_FULL) {
                    settlementService.refundFailed(
                            command.usageId(), command.failureCode());
                    lifecycle.markFailedRefunded();
                } else {
                    settlementService.markReconcileRequired(
                            command.usageId(), command.failureCode());
                    lifecycle.markReconcileRequired();
                }
                lifecycleDiagnosticService.record(
                        command.traceContext(),
                        "FINALIZER_COMPLETED",
                        AiConversationLifecycleEvent.terminal(
                                lifecycle.state().name(),
                                "ON_ERROR",
                                command.settlement() == null
                                        ? "UPSTREAM_FAILED"
                                        : command.settlement().finishReason(),
                                command.failureCode(),
                                command.action().name(),
                                lifecycle.state().name(),
                                command.settlement() != null
                                        && !command.settlement()
                                                .assistant().text().isEmpty(),
                                command.action()
                                        == AiConversationTerminalBillingAction
                                                .SETTLE_REPORTED_USAGE,
                                command.settlement() == null
                                        ? 0L
                                        : command.settlement()
                                                .assistant().text().length()));
                return;
            } catch (RuntimeException ignoredFailure) {
                // 每次尝试都是独立短事务；达到上限后只转待对账，禁止无限重放。
            }
        }
        try {
            settlementService.markReconcileRequired(
                    command.usageId(), command.failureCode());
        } catch (RuntimeException ignoredFailure) {
            // 数据库持续不可用时保持 RESERVED，由过期扫描在安全窗口后转待对账。
        }
        lifecycle.markReconcileRequired();
        lifecycleDiagnosticService.record(
                command.traceContext(),
                "RECONCILE_REQUIRED_MARKED",
                AiConversationLifecycleEvent.terminal(
                        "RECONCILE_REQUIRED",
                        "ON_ERROR",
                        "INTERRUPTED",
                        command.failureCode(),
                        command.action().name(),
                        "RECONCILE_REQUIRED",
                        false,
                        false,
                        0L));
    }

    private AiConversationSettlementCommand interruptionSettlementCommand(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            StreamState state,
            AiConversationTerminalBillingDecision decision) {
        AiConversationUsage usage = Objects.requireNonNull(decision.usage());
        return new AiConversationSettlementCommand(
                reservation.usageId(),
                null,
                command.input(),
                new AiConversationContent(state.answer.toString(), List.of()),
                tokenizer.tokenize(command.input().text()),
                usage.promptTokens(),
                usage.cachedPromptTokens(),
                usage.completionTokens(),
                usage.reasoningTokens(),
                state.upstreamRequestId.get(),
                decision.failureCode(),
                state.traceContext);
    }

    private AiConversationSettlementCommand settlementCommand(
            AiConversationResponseCommand command,
            AiConversationReservation reservation,
            Long messageId,
            AiConversationContent user,
            AiConversationContent assistant,
            StreamState state,
            AiConversationUsage usage) {
        return new AiConversationSettlementCommand(
                reservation.usageId(),
                messageId,
                user,
                assistant,
                tokenizer.tokenize(command.input().text()),
                usage.promptTokens(),
                usage.cachedPromptTokens(),
                usage.completionTokens(),
                usage.reasoningTokens(),
                state.upstreamRequestId.get(),
                Objects.requireNonNullElse(
                        state.finishReason.get(), "CLIENT_CANCELLED"),
                state.traceContext);
    }

    private AiConversationResponseStream replay(
            AiConversationReservation reservation,
            String conversationPublicId,
            String usagePublicId,
            AiModelCacheEntry model) {
        if (reservation.billingStatus()
                != AiModelBillingStatus.SETTLED.code()
                || reservation.completedMessageId() == null) {
            AiConversationErrorCode code =
                    reservation.billingStatus()
                            == AiModelBillingStatus.RECONCILE_REQUIRED.code()
                            ? AiConversationErrorCode
                                    .AI_SETTLEMENT_RECONCILE_REQUIRED
                            : AiConversationErrorCode.AI_IDEMPOTENCY_CONFLICT;
            throw new AiConversationException(
                    code,
                    "相同幂等键对应的请求仍在处理或已终止",
                    false);
        }
        AiModelUsage usage = usageMapper.findById(reservation.usageId());
        if (usage == null || usage.getChargedQuotaMinor() == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_SETTLEMENT_RECONCILE_REQUIRED,
                    "已完成请求的结算记录不可用",
                    false);
        }
        AiConversationMessage message = messageMapper.findByIdAndConversationId(
                reservation.completedMessageId(),
                reservation.conversationId());
        if (message == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_SETTLEMENT_RECONCILE_REQUIRED,
                    "已完成请求的消息记录不可用",
                    false);
        }
        List<AiConversationAttachment> inputAttachments = persistedAttachments(
                message.getContentAttachmentsJson());
        List<AiConversationAttachment> responseAttachments = persistedAttachments(
                message.getResponseAttachmentsJson());
        AiConversationStreamEvent accepted = AiConversationStreamEvent.accepted(
                new AiConversationAcceptedData(
                        conversationPublicId,
                        usagePublicId,
                        publicIdCodec.encode(model.id()),
                        false));
        List<AiConversationStreamEvent> terminalEvents = new ArrayList<>(2);
        if (message.getQuestionTokens() != null
                && !message.getQuestionTokens().isEmpty()) {
            // 幂等重放发生时客户端可能没有收到原流的任何文本片段，因此必须先恢复数据库中的完整回答。
            terminalEvents.add(AiConversationStreamEvent.delta(
                    new AiConversationDeltaData(
                            1L,
                            "TEXT",
                            message.getQuestionTokens())));
        }
        AiConversationStreamEvent completed =
                AiConversationStreamEvent.completed(
                        new AiConversationCompletedData(
                                conversationPublicId,
                                publicIdCodec.encode(
                                        reservation.completedMessageId()),
                                usagePublicId,
                                Long.toString(usage.getPromptTokens()),
                                Long.toString(usage.getCachedPromptTokens()),
                                Long.toString(usage.getCompletionTokens()),
                                Long.toString(usage.getReasoningTokens()),
                                Long.toString(usage.getChargedQuotaMinor()),
                                usage.getFinishReason(),
                                inputAttachments,
                                responseAttachments,
                                persistedAttachmentWarnings(
                                        inputAttachments,
                                        responseAttachments),
                                terminalEvents.size() + 1L));
        terminalEvents.add(completed);
        return new AiConversationResponseStream(
                accepted, Flux.fromIterable(terminalEvents));
    }

    private List<AiConversationAttachment> persistedAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(json, ATTACHMENT_LIST));
        } catch (JsonProcessingException exception) {
            // 幂等重放必须忠实返回原终态；附件快照损坏时转人工对账，禁止伪装成空附件成功响应。
            throw new AiConversationException(
                    AiConversationErrorCode.AI_SETTLEMENT_RECONCILE_REQUIRED,
                    "已完成请求的附件记录不可用",
                    false);
        }
    }

    private static List<String> persistedAttachmentWarnings(
            List<AiConversationAttachment> input,
            List<AiConversationAttachment> response) {
        boolean partial = java.util.stream.Stream.concat(
                        input.stream(), response.stream())
                .anyMatch(attachment -> attachment.state()
                        == AiConversationAttachmentState.STORAGE_FAILED);
        return partial ? List.of("ATTACHMENT_STORAGE_PARTIAL") : List.of();
    }

    private AiModelCacheEntry requiredModel(long modelId) {
        AiModelCacheEntry model = modelCacheService
                .getOrLoadEnabledSnapshot()
                .models()
                .stream()
                .filter(candidate -> candidate.id() == modelId)
                .findFirst()
                .orElseThrow(() -> new AiConversationException(
                        AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                        "模型不存在或当前未启用",
                        false));
        if (model.contextWindowTokens() <= 0L
                || model.maxOutputTokens() <= 0L) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_LIMITS_MISSING,
                    "模型缺少上下文窗口或最大输出限制",
                    false);
        }
        return model;
    }

    private static void validateProtocolCapabilities(
            AiModelCacheEntry model,
            AiConversationWebSearchMode webSearchMode) {
        boolean supported = webSearchMode == AiConversationWebSearchMode.OFF
                ? model.capabilities().contains(
                        AiModelCapabilityCode.CHAT_COMPLETIONS)
                : model.capabilities().contains(AiModelCapabilityCode.RESPONSES)
                        && model.capabilities().contains(
                                AiModelCapabilityCode.WEB_SEARCH);
        if (!supported) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_MODEL_NOT_AVAILABLE,
                    webSearchMode == AiConversationWebSearchMode.OFF
                            ? "模型不支持普通对话协议"
                            : "模型不支持 Responses 联网搜索",
                    false);
        }
    }

    private void validateWebSearchEnabled(
            AiConversationWebSearchMode webSearchMode) {
        if (webSearchMode != AiConversationWebSearchMode.OFF
                && !webSearchProperties.enabled()) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "联网搜索功能当前未启用",
                    true);
        }
    }

    private void releaseConcurrency(
            AiConversationConcurrencyPermit permit) {
        try {
            concurrencyService.release(permit);
        } catch (RuntimeException ignoredFailure) {
            // 释放失败由 ZSET 成员绝对过期时间收敛，不能覆盖业务结果。
        }
    }

    private AiConversationPromptSnapshot prepareExistingWithEmergencyCompaction(
            AiConversationResponseCommand command,
            AiModelCacheEntry model) {
        String conversationPublicId =
                hybridIdCodec.encode(command.conversationId());
        try {
            return contextService.prepare(
                    command.conversationId(),
                    conversationPublicId,
                    model,
                    command.input());
        } catch (AiConversationException exception) {
            if (exception.code() != AiConversationErrorCode.AI_CONTEXT_TOO_LARGE) {
                throw exception;
            }
            String generation = contextStore.find(conversationPublicId)
                    .map(snapshot -> snapshot.generation())
                    .orElse(null);
            if (generation != null
                    && compactionService.compactEphemeralSynchronously(
                            conversationPublicId, generation)) {
                try {
                    return contextService.prepare(
                            command.conversationId(),
                            conversationPublicId,
                            model,
                            command.input());
                } catch (AiConversationException afterEphemeral) {
                    if (afterEphemeral.code()
                            != AiConversationErrorCode.AI_CONTEXT_TOO_LARGE) {
                        throw afterEphemeral;
                    }
                }
            }
            if (!compactionService.compactSynchronously(
                    command.conversationId(),
                    conversationPublicId,
                    generation)) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_CONTEXT_COMPACTION_FAILED,
                        "会话上下文压缩失败",
                        true);
            }
            return contextService.prepare(
                    command.conversationId(),
                    conversationPublicId,
                    model,
                    command.input());
        }
    }

    private void validateExistingConversation(
            AiConversationResponseCommand command) {
        if (command.conversationId() != null
                && conversationMapper.findActiveOwned(
                        command.conversationId(), command.userId()) == null) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_CONVERSATION_NOT_FOUND,
                    "会话不存在或不可用",
                    false);
        }
    }

    private void validateCommand(AiConversationResponseCommand command) {
        if (command.userId() <= 0L
                || command.userPublicId() == null
                || command.modelPublicId() == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().version() != 4
                || command.input() == null
                || (command.input().text().isBlank()
                    && command.input().uploadReferences().isEmpty())
                || command.input().text().getBytes(StandardCharsets.UTF_8).length
                        > 64 * 1024
                || command.input().uploadReferences().size() > 8
                || !command.input().attachments().isEmpty()) {
            throw invalidRequest("AI conversation request is invalid.");
        }
    }

    private static void validateAttachmentCapabilities(
            AiModelCacheEntry model,
            List<AiConversationAttachment> attachments) {
        for (AiConversationAttachment attachment : attachments) {
            AiModelCapabilityCode required = switch (attachment.category()) {
                case IMAGE -> AiModelCapabilityCode.IMAGE_INPUT;
                case AUDIO -> AiModelCapabilityCode.AUDIO_INPUT;
                case VIDEO -> AiModelCapabilityCode.VIDEO_INPUT;
                case DOCUMENT, ARCHIVE, OTHER -> null;
            };
            // 文档、压缩包和其他附件仍可持久保存，但本期不会作为模型媒体发送。
            if (required != null && !model.capabilities().contains(required)) {
                throw new AiConversationException(
                        AiConversationErrorCode.AI_ATTACHMENT_CAPABILITY_UNSUPPORTED,
                        "当前模型不支持附件类型：" + attachment.category().name(),
                        false);
            }
        }
    }

    private static AiConversationException invalidRequest(String message) {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                message,
                false);
    }

    private static void appendBounded(StreamState state, String text) {
        int textBytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (state.answerBytes + textBytes > MAX_ASSISTANT_BYTES) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED,
                    "模型回答超过服务端允许的最大流大小",
                    false);
        }
        state.answer.append(text);
        state.answerBytes += textBytes;
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

    private static String generatedMediaFingerprint(
            AiConversationGeneratedMedia media) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(media.fileName().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(media.contentType().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(media.bytes());
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void recordTerminalPolicyDecision(
            StreamState state,
            String outcome,
            String reactorSignal,
            AiConversationTerminalBillingDecision decision) {
        lifecycleDiagnosticService.record(
                state.traceContext,
                "TERMINAL_POLICY_DECIDED",
                AiConversationLifecycleEvent.terminal(
                        outcome,
                        reactorSignal,
                        state.finishReason.get(),
                        decision.failureCode(),
                        decision.action().name(),
                        "unavailable",
                        state.answer.length() > 0,
                        state.usage.get() != null,
                        state.answer.length()));
    }

    private static AiConversationLifecycleEvent lifecycleStateEvent(
            AiConversationRequestState stateBefore,
            AiConversationRequestState stateAfter,
            SignalType signal) {
        return AiConversationLifecycleEvent.lifecycleState(
                signal.name(),
                stateBefore.name(),
                stateAfter.name());
    }

    private static String upstreamOutcome(SignalType signal) {
        return switch (signal) {
            case ON_COMPLETE -> "COMPLETED";
            case CANCEL -> "CANCELLED";
            case ON_ERROR -> "FAILED";
            default -> "TERMINATED";
        };
    }

    private static String diagnosticFailureCode(Throwable failure) {
        return failure instanceof AiConversationException controlled
                ? controlled.code().name()
                : AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED.name();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String currentClientRequestId() {
        String clientRequestId = MDC.get("aiClientRequestId");
        if (clientRequestId == null
                || clientRequestId.isBlank()
                || clientRequestId.length() > 128
                || !clientRequestId.matches("^[A-Za-z0-9_-]+$")) {
            return "unavailable";
        }
        return clientRequestId;
    }

    private static long currentRequestStartedNanos() {
        String startedNanos = MDC.get("aiRequestStartedNanos");
        if (startedNanos != null) {
            try {
                long parsed = Long.parseLong(startedNanos);
                if (parsed > 0L && parsed <= System.nanoTime()) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // 非法 MDC 值不能污染单调计时原点，非 Web 调用退回当前进程时间。
            }
        }
        return System.nanoTime();
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        if (traceId == null
                || traceId.isBlank()
                || traceId.length() > 128) {
            return "unavailable";
        }
        for (int index = 0; index < traceId.length(); index++) {
            char current = traceId.charAt(index);
            if (!Character.isLetterOrDigit(current)
                    && current != '-'
                    && current != '_') {
                return "unavailable";
            }
        }
        return traceId;
    }

    private static final class StreamState {
        private final AiConversationRequestLifecycle lifecycle;
        private volatile long ephemeralOrdinal;
        private final AtomicReference<String> cacheGeneration;
        private final AiConversationContent input;
        private final String usagePublicId;
        private final String modelPublicId;
        private final String traceId;
        private final AiConversationLifecycleTraceContext traceContext;
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean firstByteRecorded = new AtomicBoolean();
        private final AtomicBoolean upstreamTerminalRecorded = new AtomicBoolean();
        private final AtomicBoolean successWorkStarted = new AtomicBoolean();
        private final AtomicBoolean requestOutcomeRecorded = new AtomicBoolean();
        private final AtomicBoolean diagnosticRecorded = new AtomicBoolean();
        private final AtomicBoolean generationResourcesReleased =
                new AtomicBoolean();
        private final StringBuilder answer = new StringBuilder();
        private final List<AiConversationGeneratedMedia> generatedMedia =
                new ArrayList<>();
        private final Set<String> generatedMediaFingerprints = new HashSet<>();
        private long generatedMediaBytes;
        private boolean generatedMediaRejected;
        private int answerBytes;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicReference<AiConversationInterruptionSource>
                interruptionSource = new AtomicReference<>(
                        AiConversationInterruptionSource.TRANSPORT_DISCONNECT);
        private HmacIdentifier directRequestIdentifier;
        private AiConversationDirectResponseCancellationHandle cancellationHandle;
        private final AtomicReference<AiConversationUsage> candidateUsage =
                new AtomicReference<>();
        private final AtomicBoolean terminalFinishObserved = new AtomicBoolean();
        private final AtomicBoolean reasoningStarted = new AtomicBoolean();
        private final AtomicBoolean generatingStarted = new AtomicBoolean();
        private final AiConversationActivityEventDeduplicator
                researchActivityEvents =
                        new AiConversationActivityEventDeduplicator();
        private final Set<String> researchSourceKeys = new HashSet<>();
        private final AtomicReference<AiConversationUsage> usage =
                new AtomicReference<>();
        private final AtomicReference<String> upstreamRequestId =
                new AtomicReference<>();
        private final AtomicReference<String> finishReason =
                new AtomicReference<>();
        private final AtomicReference<AiConversationStreamFailureClassification>
                failureClassification = new AtomicReference<>();

        private StreamState(
                AiConversationRequestLifecycle lifecycle,
                long ephemeralOrdinal,
                String cacheGeneration,
                AiConversationContent input,
                String usagePublicId,
                String modelPublicId,
                AiConversationLifecycleTraceContext traceContext) {
            this.lifecycle = lifecycle;
            this.ephemeralOrdinal = ephemeralOrdinal;
            this.cacheGeneration = new AtomicReference<>(cacheGeneration);
            this.input = input;
            this.usagePublicId = usagePublicId;
            this.modelPublicId = modelPublicId;
            this.traceContext = Objects.requireNonNull(traceContext);
            this.traceId = traceContext.traceId();
        }
    }
}
