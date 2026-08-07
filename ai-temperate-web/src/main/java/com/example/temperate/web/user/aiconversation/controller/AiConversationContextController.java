package com.example.temperate.web.user.aiconversation.controller;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionCoordinator;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionRequestResult;
import com.example.temperate.service.user.aiconversation.compaction.AiConversationCompactionTrigger;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEvent;
import com.example.temperate.service.user.aiconversation.context.event.AiConversationContextEventService;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsage;
import com.example.temperate.service.user.aiconversation.context.usage.AiConversationContextUsageService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.web.aiconversation.AiConversationPublicId;
import com.example.temperate.web.user.aiconversation.api.AiConversationCompactionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 为普通用户提供会话上下文用量快照、异步压缩请求和按需状态 SSE。
 *
 * <p>Controller 只负责公共 ID、认证主体和 HTTP 协议编排；Token 与阈值始终由后端服务计算。</p>
 */
@Validated
@RestController
@RequestMapping("/api/ai/conversations/{conversationPublicId}")
@Tag(
        name = "用户-AI 会话上下文",
        description = "供已认证 H5 和 Android 用户查询上下文占用、请求异步压缩并按需观察状态。"
                + "接口执行会话资源级授权，不接受客户端提交 Token 总量或百分比。")
public final class AiConversationContextController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AiConversationContextUsageService usageService;
    private final AiConversationCompactionCoordinator compactionCoordinator;
    private final AiConversationContextEventService eventService;

    public AiConversationContextController(
            AiConversationContextUsageService usageService,
            AiConversationCompactionCoordinator compactionCoordinator,
            AiConversationContextEventService eventService) {
        this.usageService = Objects.requireNonNull(usageService);
        this.compactionCoordinator = Objects.requireNonNull(compactionCoordinator);
        this.eventService = Objects.requireNonNull(eventService);
    }

    @GetMapping(path = "/context-usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "查询会话上下文占用",
            description = "按当前启用模型窗口返回 Redis v2 Token 快照；返回值是保守估算，不用于资金结算。")
    public ResponseEntity<AiConversationContextUsage> usage(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable AiConversationPublicId conversationPublicId,
            @RequestParam
            @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN)
            String modelPublicId) {
        AiConversationContextUsage usage = usageService.getOwned(
                principal.userId(),
                conversationPublicId.internalValue(),
                conversationPublicId.encoded(),
                modelPublicId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(usage);
    }

    @PostMapping(
            path = "/compactions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "请求异步压缩会话上下文",
            description = "后端重新校验 80% 阈值；未达到返回 200，创建或复用单飞任务返回 202。")
    public ResponseEntity<AiConversationCompactionRequestResult> compact(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable AiConversationPublicId conversationPublicId,
            @RequestHeader(IDEMPOTENCY_HEADER)
            @Parameter(
                    name = IDEMPOTENCY_HEADER,
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "本次压缩意图的 UUIDv4",
                    schema = @Schema(type = "string", format = "uuid"))
            String idempotencyKey,
            @Valid @RequestBody AiConversationCompactionRequest request) {
        AiConversationCompactionRequestResult result =
                compactionCoordinator.requestOwned(
                        principal.userId(),
                        conversationPublicId.internalValue(),
                        conversationPublicId.encoded(),
                        request.modelPublicId(),
                        uuidV4(idempotencyKey),
                        AiConversationCompactionTrigger.MODEL_SWITCH);
        HttpStatus status = result.accepted()
                ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(result);
    }

    @GetMapping(path = "/context/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "按需观察上下文和压缩状态",
            description = "先订阅 Redis 通知再发送权威快照，使用 eventRevision 支持断线续接和事件去重。")
    public ResponseEntity<Flux<ServerSentEvent<Object>>> events(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable AiConversationPublicId conversationPublicId,
            @RequestParam
            @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN)
            String modelPublicId,
            @RequestParam(defaultValue = "0")
            @Min(0)
            long afterRevision) {
        Flux<ServerSentEvent<Object>> body = eventService.observe(
                        principal.userId(),
                        conversationPublicId.internalValue(),
                        conversationPublicId.encoded(),
                        modelPublicId,
                        afterRevision)
                .map(AiConversationContextController::sse);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    private static ServerSentEvent<Object> sse(AiConversationContextEvent event) {
        return ServerSentEvent.builder((Object) event.data())
                .event(event.name())
                .id(Long.toString(event.data().eventRevision()))
                .build();
    }

    private static UUID uuidV4(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (uuid.version() == 4 && uuid.variant() == 2) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // 统一转换为稳定的请求错误，避免把 JDK 解析细节暴露给客户端。
        }
        throw new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "Idempotency-Key must be a UUIDv4.",
                false);
    }
}
