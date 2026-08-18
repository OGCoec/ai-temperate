package com.example.temperate.web.user.aiconversation.controller;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.history.AiConversationHistoryPage;
import com.example.temperate.service.user.aiconversation.history.AiConversationHistoryService;
import com.example.temperate.service.user.aiconversation.history.AiConversationPage;
import com.example.temperate.web.aiconversation.AiConversationPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为普通用户提供只读会话侧栏和 PostgreSQL 完整消息历史，不合并 Redis 中的中断回答。
 */
@Validated
@RestController
@RequestMapping("/api/ai/conversations")
@Tag(
        name = "用户-AI 会话历史",
        description = "供已认证普通用户读取自己的有效会话和已完成消息；接口不返回内部 ID，"
                + "也不暴露只存在于 Redis 上下文中的中断回答。")
public class AiConversationQueryController {

    private final AiConversationHistoryService historyService;

    public AiConversationQueryController(
            AiConversationHistoryService historyService) {
        this.historyService = Objects.requireNonNull(historyService);
    }

    @GetMapping
    @Operation(summary = "分页读取当前用户会话侧栏")
    public ResponseEntity<AiConversationPage> conversations(
            @AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam(required = false)
            @Pattern(regexp = "^[A-Za-z0-9_-]{32}$")
            String cursor,
            @RequestParam(defaultValue = "18")
            @Min(1) @Max(50)
            int pageSize) {
        return noStore(historyService.list(principal.userId(), cursor, pageSize));
    }

    @GetMapping("/{conversationPublicId}/messages")
    @Operation(
            summary = "分页读取会话完整消息",
            description = "只读取 PostgreSQL 中已经完成并结算的消息；返回按时间正序排列的当前页。")
    public ResponseEntity<AiConversationHistoryPage> messages(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(
                    description = "会话的 22 字符规范 Base64URL 公共 ID",
                    schema = @Schema(
                            minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                            pattern = HybridBase64UrlCodec.ENCODED_PATTERN))
            AiConversationPublicId conversationPublicId,
            @RequestParam(required = false)
            @Pattern(regexp = PublicIdCodec.ENCODED_PATTERN)
            String before,
            @RequestParam(defaultValue = "50")
            @Min(1) @Max(100)
            int pageSize) {
        return noStore(historyService.messages(
                principal.userId(),
                conversationPublicId.internalValue(),
                before,
                pageSize));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
    }
}
