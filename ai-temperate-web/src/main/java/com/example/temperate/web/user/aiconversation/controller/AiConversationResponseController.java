package com.example.temperate.web.user.aiconversation.controller;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentUploadReference;
import com.example.temperate.service.user.aiconversation.context.AiConversationContent;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationRequest;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationStart;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverSession;
import com.example.temperate.service.user.aiconversation.response.AiConversationAcceptedData;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationService;
import com.example.temperate.service.user.aiconversation.response.AiConversationDirectResponseCancellationStatus;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseCommand;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseService;
import com.example.temperate.service.user.aiconversation.response.AiConversationResponseStream;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.example.temperate.web.aiconversation.AiConversationPublicId;
import com.example.temperate.web.user.aiconversation.api.AiConversationResponseRequest;
import com.example.temperate.web.user.aiconversation.api.AiConversationDirectResponseCancelResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 为普通用户提供创建会话和继续会话的 POST SSE 接口，只负责编解码、认证主体和流协议响应头。
 *
 * <p>功能开关关闭时委托同步响应 Service；开启时只创建 Generation 并建立首个 Observer。
 * Controller 不接收内部数据库 ID，也不会把 API Key、Redis Key 或上游完整错误暴露给客户端。</p>
 */
@RestController
@RequestMapping("/api/ai/conversations")
@Tag(
        name = "用户-AI 会话响应",
        description = "供已认证 H5 和 Android 用户以 POST SSE 创建或继续 AI 会话。"
                + "接口经过现有 RT-first 会话、设备和风险校验，不负责前端 EventSource 兼容或管理员配置。")
public final class AiConversationResponseController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AiConversationResponseService responseService;
    private final ObjectProvider<AiConversationGenerationService> generationServiceProvider;
    private final ObjectProvider<AiConversationGenerationObserverService> observerServiceProvider;
    private final HybridBase64UrlCodec hybridIdCodec;
    private final AiConversationDirectResponseCancellationService cancellationService;

    @Autowired
    public AiConversationResponseController(
            AiConversationResponseService responseService,
            ObjectProvider<AiConversationGenerationService> generationServiceProvider,
            ObjectProvider<AiConversationGenerationObserverService> observerServiceProvider,
            HybridBase64UrlCodec hybridIdCodec,
            AiConversationDirectResponseCancellationService cancellationService) {
        this.responseService = Objects.requireNonNull(responseService);
        this.generationServiceProvider = Objects.requireNonNull(generationServiceProvider);
        this.observerServiceProvider = Objects.requireNonNull(observerServiceProvider);
        this.hybridIdCodec = Objects.requireNonNull(hybridIdCodec);
        this.cancellationService = Objects.requireNonNull(cancellationService);
    }

    /**
     * 仅供现有独立 Controller 单元测试构造旧同步路径；Spring 运行时固定使用完整构造器。
     */
    public AiConversationResponseController(AiConversationResponseService responseService) {
        this.responseService = Objects.requireNonNull(responseService);
        this.generationServiceProvider = null;
        this.observerServiceProvider = null;
        this.hybridIdCodec = null;
        this.cancellationService = null;
    }

    @PostMapping(
            path = "/responses",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {
                    MediaType.TEXT_EVENT_STREAM_VALUE,
                    MediaType.APPLICATION_JSON_VALUE
            })
    @Operation(
            summary = "创建会话并流式生成第一条回答",
            description = "页面创建不落库；本接口预扣成功后由服务端生成 22 字符会话公共 ID，"
                    + "并发送 accepted、delta、heartbeat、completed/error 或视频 progress/transfer/ready/failed 事件。")
    public Mono<ResponseEntity<Flux<ServerSentEvent<Object>>>> createAndRespond(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @Parameter(
                    name = IDEMPOTENCY_HEADER,
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "本次发送动作的 UUIDv4；网络重试必须复用，主动重新提问必须更换",
                    schema = @Schema(
                            type = "string",
                            format = "uuid",
                            example = "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6"))
            String idempotencyKey,
            @Valid @RequestBody AiConversationResponseRequest request) {
        return response(
                principal,
                null,
                idempotencyKey,
                request);
    }

    @PostMapping(
            path = "/{conversationPublicId}/responses",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {
                    MediaType.TEXT_EVENT_STREAM_VALUE,
                    MediaType.APPLICATION_JSON_VALUE
            })
    @Operation(
            summary = "继续已有会话并流式生成回答",
            description = "会话公共 ID 必须属于当前用户；同一会话同时只允许一个活跃生成。")
    public Mono<ResponseEntity<Flux<ServerSentEvent<Object>>>> continueAndRespond(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "会话的 22 字符规范 Base64URL 公共 ID",
                    schema = @Schema(
                            type = "string",
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.ENCODED_PATTERN,
                            example = "AZ-vpV3kfag70-0EMMUETQ"))
            AiConversationPublicId conversationPublicId,
            @RequestHeader(IDEMPOTENCY_HEADER)
            String idempotencyKey,
            @Valid @RequestBody AiConversationResponseRequest request) {
        return response(
                principal,
                conversationPublicId,
                idempotencyKey,
                request);
    }

    @PostMapping(
            path = "/responses/cancel",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "幂等停止当前直接流",
            description = "使用原始发送请求的 UUIDv4 幂等键请求停止；接口只返回有限状态，"
                    + "模型正文和用户身份不会进入响应。")
    public ResponseEntity<AiConversationDirectResponseCancelResponse> cancel(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @Parameter(
                    name = IDEMPOTENCY_HEADER,
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "必须复用原始发送请求的 UUIDv4 幂等键",
                    schema = @Schema(
                            type = "string",
                            format = "uuid",
                            example = "4f7b5d34-3a0e-4d91-8fc2-65b7c8b141d6"))
            String idempotencyKey) {
        UUID idempotencyUuid = parseIdempotencyKey(idempotencyKey);
        AiConversationDirectResponseCancellationStatus status =
                cancellationService.requestUserStop(
                        principal.userId(),
                        idempotencyUuid,
                        currentTraceId());
        ResponseEntity.BodyBuilder builder = status
                        == AiConversationDirectResponseCancellationStatus
                                .CANCEL_REQUESTED
                ? ResponseEntity.status(HttpStatus.ACCEPTED)
                : ResponseEntity.ok();
        return builder.body(new AiConversationDirectResponseCancelResponse(
                status.name()));
    }

    private Mono<ResponseEntity<Flux<ServerSentEvent<Object>>>> response(
            SessionPrincipal principal,
            AiConversationPublicId conversationPublicId,
            String idempotencyKey,
            AiConversationResponseRequest request) {
        UUID idempotencyUuid = parseIdempotencyKey(idempotencyKey);
        AiConversationResponseCommand command = new AiConversationResponseCommand(
                        principal.userId(),
                        principal.publicId(),
                        conversationPublicId == null
                                ? null
                                : conversationPublicId.internalValue(),
                        request.modelPublicId(),
                        AiConversationReasoningEffort.fromLevel(
                                request.reasoningEffortLevel()),
                        request.webSearchMode(),
                        request.image() == null
                                ? null
                                : new AiConversationImageGenerationRequest(
                                        request.image().aspect(),
                                        request.image().outputCount()),
                        request.video() == null
                                ? null
                                : new com.example.temperate.service.user.aiconversation.video
                                        .AiConversationVideoGenerationRequest(
                                                request.video().mode(),
                                                request.video().durationSeconds(),
                                                request.video().resolution(),
                                                request.video().aspectRatio(),
                                                request.video().inputAttachmentPublicIds()),
                        idempotencyUuid,
                        new AiConversationContent(
                                request.input().text(),
                                List.of(),
                                request.input().attachments().stream()
                                        .map(attachment ->
                                                new AiConversationAttachmentUploadReference(
                                                        attachment.uploadSessionId(),
                                                        attachment.attachmentId(),
                                                        attachment.fileName(),
                                                        attachment.contentType(),
                                                        Long.parseLong(attachment.sizeBytes())))
                                        .toList()));
        AiConversationGenerationService generationService = generationServiceProvider == null
                ? null
                : generationServiceProvider.getIfAvailable();
        if (generationService != null
                && (request.image() != null
                        || request.video() != null
                        || request.webSearchMode() == AiConversationWebSearchMode.OFF)) {
            // 图片请求必须进入 Generation 服务统一拒绝联网组合；只有纯文本联网请求继续走直接 POST SSE 研究协议。
            return asyncResponse(
                    principal,
                    command,
                    generationService,
                    Objects.requireNonNull(observerServiceProvider.getIfAvailable()));
        }
        if (command.imageGeneration() != null || command.videoGeneration() != null) {
            // 媒体生成与最终 OSS URL 只在异步 Generation 链路中有明确边界；该链路关闭时必须失败封闭，禁止误降级成文本调用。
            return Mono.error(new AiConversationException(
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE,
                    "媒体生成需要启用异步 Generation 链路。",
                    true));
        }
        return responseService.respondAsync(command)
                .map(AiConversationResponseController::directSseResponse);
    }

    private static ResponseEntity<Flux<ServerSentEvent<Object>>> directSseResponse(
            AiConversationResponseStream stream) {
        AiConversationAcceptedData accepted = (AiConversationAcceptedData) stream.accepted().data();
        Flux<ServerSentEvent<Object>> body = Flux.concat(
                        Flux.just(stream.accepted()),
                        stream.events())
                .map(AiConversationResponseController::sse);
        // Usage 公共编号在响应头阶段即可获得，使 Android 能在首个正文块之前关联安全流诊断。
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "private, no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .header("X-AI-Usage-Id", accepted.usagePublicId())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private Mono<ResponseEntity<Flux<ServerSentEvent<Object>>>> asyncResponse(
            SessionPrincipal principal,
            AiConversationResponseCommand command,
            AiConversationGenerationService generationService,
            AiConversationGenerationObserverService observerService) {
        return generationService.createAsync(command)
                .map(start -> generationSseResponse(
                        principal, start, observerService));
    }

    private ResponseEntity<Flux<ServerSentEvent<Object>>> generationSseResponse(
            SessionPrincipal principal,
            AiConversationGenerationStart start,
            AiConversationGenerationObserverService observerService) {
        AiConversationGenerationObserverSession observer = observerService.observe(
                principal.userId(),
                hybridIdCodec.decode(start.generationPublicId()));
        AiConversationStreamEvent accepted = AiConversationStreamEvent.accepted(
                new AiConversationAcceptedData(
                        start.conversationPublicId(),
                        start.usagePublicId(),
                        start.modelPublicId(),
                        start.newConversation(),
                        start.generationPublicId()));
        Flux<ServerSentEvent<Object>> body = Flux.concat(
                        Flux.just(accepted),
                        observer.events())
                .map(AiConversationResponseController::sse);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .header("X-AI-Generation-Id", start.generationPublicId())
                .header("X-AI-Usage-Id", start.usagePublicId())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private static AiConversationException invalidIdempotencyKey() {
        return new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "Idempotency-Key must be a UUIDv4.",
                false);
    }

    private static UUID parseIdempotencyKey(String idempotencyKey) {
        try {
            if (idempotencyKey == null) {
                throw invalidIdempotencyKey();
            }
            UUID idempotencyUuid = UUID.fromString(idempotencyKey);
            if (idempotencyUuid.version() != 4
                    || idempotencyUuid.variant() != 2) {
                throw invalidIdempotencyKey();
            }
            return idempotencyUuid;
        } catch (IllegalArgumentException exception) {
            throw invalidIdempotencyKey();
        }
    }

    private static String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId != null && traceId.matches("[A-Za-z0-9_-]{1,128}")
                ? traceId
                : "unavailable";
    }

    private static ServerSentEvent<Object> sse(
            AiConversationStreamEvent event) {
        return ServerSentEvent.builder(event.data())
                .event(event.name())
                .build();
    }
}
