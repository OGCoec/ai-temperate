package com.example.temperate.web.user.aiconversation.controller;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamClientDiagnosticService;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.web.aiconversation.AiConversationGenerationPublicId;
import com.example.temperate.web.user.aiconversation.api.AiConversationStreamDiagnosticsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收已认证用户对自己 Generation 的浏览器流式诊断摘要，供后端、Redis 和边缘日志关联排查。
 * 该 Controller 不接收正文，不改变会话业务状态，也不提供未认证的诊断写入口。
 */
@RestController
@RequestMapping("/api/ai/conversations/generations")
@ConditionalOnExpression("${app.ai-conversation.async-generation.enabled:false}"
        + " && ${app.ai-conversation.stream-diagnostics.enabled:false}")
@Tag(
        name = "用户-AI 流式诊断",
        description = "供已认证普通用户在诊断开关打开时回传自己 Generation 的 SSE 时间摘要；"
                + "只接收耗时、计数、revision 序号和关联 ID，不接收模型正文或认证凭据。")
public final class AiConversationStreamDiagnosticsController {

    private static final long MAX_REQUEST_BYTES = 2_048L;

    private final AiConversationStreamClientDiagnosticService diagnosticService;

    public AiConversationStreamDiagnosticsController(
            AiConversationStreamClientDiagnosticService diagnosticService) {
        this.diagnosticService = Objects.requireNonNull(diagnosticService);
    }

    @PostMapping("/{generationPublicId}/stream-diagnostics")
    @Operation(
            summary = "回传一次 SSE 客户端时间摘要",
            description = "Generation 公共 ID 必须属于当前已认证用户；服务端会限制摘要大小和重复提交，"
                    + "请求不得包含模型正文、Prompt、Token 或其他敏感数据。")
    public ResponseEntity<Void> record(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "Generation 的 22 字符规范 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = 22,
                            maxLength = 22,
                            pattern = "^[A-Za-z0-9_-]{22}$"))
            AiConversationGenerationPublicId generationPublicId,
            @RequestHeader(value = "Content-Length", required = false)
            Long contentLength,
            @Valid @RequestBody AiConversationStreamDiagnosticsRequest request) {
        // 浏览器汇总正常只有数百字节；先拒绝异常大的负载，避免诊断入口成为通用 JSON 吞吐通道。
        if (contentLength != null && (contentLength < 0L || contentLength > MAX_REQUEST_BYTES)) {
            throw new AiConversationException(
                    AiConversationErrorCode.AI_REQUEST_INVALID,
                    "AI stream diagnostic payload is too large.",
                    false);
        }
        diagnosticService.record(
                principal.userId(),
                generationPublicId.internalValue(),
                request.toDiagnostic());
        return ResponseEntity.noContent().build();
    }
}
