package com.example.temperate.web.user.aiconversation.controller;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationService;
import com.example.temperate.service.user.aiconversation.generation.AiConversationGenerationView;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationResult;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverService;
import com.example.temperate.service.user.aiconversation.generation.observer.AiConversationGenerationObserverSession;
import com.example.temperate.service.user.aiconversation.response.AiConversationStreamEvent;
import com.example.temperate.web.aiconversation.AiConversationGenerationPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 为已认证用户提供异步 Generation 状态、幂等恢复、SSE 重连和显式 Stop 接口。
 *
 * <p>该 Controller 不接受取消来源，不暴露内部 ID，也不会把 SSE 错误直接映射为资金终态。</p>
 */
@RestController
@RequestMapping("/api/ai/conversations/generations")
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "用户-AI 后台生成",
        description = "供已认证 H5 和 Android 用户查询后台 Generation、在三十秒内恢复观察流以及显式停止自己拥有的任务；不提供管理员取消或金额计算。")
public final class AiConversationGenerationController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AiConversationGenerationService generationService;
    private final AiConversationGenerationObserverService observerService;
    private final AiConversationGenerationCancellationService cancellationService;

    public AiConversationGenerationController(
            AiConversationGenerationService generationService,
            AiConversationGenerationObserverService observerService,
            AiConversationGenerationCancellationService cancellationService) {
        this.generationService = Objects.requireNonNull(generationService);
        this.observerService = Objects.requireNonNull(observerService);
        this.cancellationService = Objects.requireNonNull(cancellationService);
    }

    @GetMapping("/{generationPublicId}")
    @Operation(summary = "查询后台生成状态")
    public AiConversationGenerationView get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "Generation 的 22 字符规范 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.ENCODED_PATTERN))
            AiConversationGenerationPublicId generationPublicId) {
        return generationService.getOwned(
                principal.userId(), generationPublicId.internalValue());
    }

    @GetMapping
    @Operation(summary = "查询当前用户的活动生成任务")
    public List<AiConversationGenerationView> active(
            @AuthenticationPrincipal SessionPrincipal principal) {
        return generationService.listActiveOwned(principal.userId());
    }

    @GetMapping("/by-idempotency")
    @Operation(summary = "使用原始幂等键恢复生成标识")
    public AiConversationGenerationView byIdempotency(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey) {
        return generationService.getOwnedByIdempotency(
                principal.userId(), uuidV4(idempotencyKey));
    }

    @GetMapping(
            path = "/{generationPublicId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "重连后台生成的快照与实时事件")
    public ResponseEntity<Flux<ServerSentEvent<Object>>> events(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "要重连观察的 Generation 公共 ID",
                    schema = @Schema(
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.ENCODED_PATTERN))
            AiConversationGenerationPublicId generationPublicId) {
        AiConversationGenerationObserverSession observer = observerService.observe(
                principal.userId(), generationPublicId.internalValue());
        Flux<ServerSentEvent<Object>> body = observer.events()
                .map(AiConversationGenerationController::sse);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .header("X-AI-Generation-Id", observer.generationPublicId())
                .header("X-AI-Usage-Id", observer.usagePublicId())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    @PostMapping("/{generationPublicId}/cancel")
    @Operation(summary = "用户显式停止后台生成")
    public ResponseEntity<AiConversationGenerationCancellationResult> cancel(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "当前用户要停止的 Generation 公共 ID",
                    schema = @Schema(
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.ENCODED_PATTERN))
            AiConversationGenerationPublicId generationPublicId) {
        AiConversationGenerationCancellationResult result =
                cancellationService.requestUserStop(
                        principal.userId(), generationPublicId.internalValue());
        return "CANCEL_REQUESTED".equals(result.status())
                ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }

    private static UUID uuidV4(String value) {
        try {
            UUID uuid = UUID.fromString(value);
            if (uuid.version() == 4 && uuid.variant() == 2) {
                return uuid;
            }
        } catch (IllegalArgumentException ignored) {
            // 统一在下方转换为受控请求错误。
        }
        throw new AiConversationException(
                AiConversationErrorCode.AI_REQUEST_INVALID,
                "Idempotency-Key must be a UUIDv4.",
                false);
    }

    private static ServerSentEvent<Object> sse(AiConversationStreamEvent event) {
        return ServerSentEvent.builder(event.data()).event(event.name()).build();
    }
}
