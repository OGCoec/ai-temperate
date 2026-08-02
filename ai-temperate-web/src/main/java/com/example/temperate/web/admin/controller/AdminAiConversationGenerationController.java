package com.example.temperate.web.admin.controller;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationResult;
import com.example.temperate.service.user.aiconversation.generation.cancellation.AiConversationGenerationCancellationService;
import com.example.temperate.web.aiconversation.AiConversationGenerationPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为受管理员会话、设备和 CSRF 保护的后台提供 Generation 显式取消接口，不接受客户端伪造取消来源。
 */
@RestController
@RequestMapping("/api/admin/ai/conversation-generations")
@ConditionalOnProperty(
        prefix = "app.ai-conversation.async-generation",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "管理员-AI 会话生成",
        description = "供已登录管理员取消指定后台 Generation；接口固定记录 ADMIN_CANCEL，不负责查看正文、修改计费规则或直接退款。")
public final class AdminAiConversationGenerationController {

    private final AiConversationGenerationCancellationService cancellationService;

    public AdminAiConversationGenerationController(
            AiConversationGenerationCancellationService cancellationService) {
        this.cancellationService = Objects.requireNonNull(cancellationService);
    }

    @PostMapping("/{generationPublicId}/cancel")
    @Operation(summary = "管理员显式取消后台生成")
    public ResponseEntity<AiConversationGenerationCancellationResult> cancel(
            @PathVariable
            @Parameter(
                    description = "管理员要取消的 Generation 公共 ID",
                    schema = @Schema(
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.ENCODED_PATTERN))
            AiConversationGenerationPublicId generationPublicId) {
        AiConversationGenerationCancellationResult result =
                cancellationService.requestAdminCancel(
                        generationPublicId.internalValue());
        return "CANCEL_REQUESTED".equals(result.status())
                ? ResponseEntity.accepted().body(result)
                : ResponseEntity.ok(result);
    }
}
