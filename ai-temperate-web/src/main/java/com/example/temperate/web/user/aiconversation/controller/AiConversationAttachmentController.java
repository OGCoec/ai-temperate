package com.example.temperate.web.user.aiconversation.controller;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentService;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadFile;
import com.example.temperate.web.user.aiconversation.api.AiConversationPreuploadRequest;
import com.example.temperate.web.user.aiconversation.api.AiConversationPreuploadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为已认证普通用户提供会话附件批量预上传 API，不接收 Bucket、Object Key、内部用户 ID 或最终 URL。
 */
@RestController
@RequestMapping("/api/ai/conversation-attachments")
@Tag(
        name = "用户-AI 会话附件",
        description = "供已认证 H5 和 Android 用户为 AI 会话申请私有 OSS 预签名 PUT 地址。"
                + "接口只创建短期上传条件，不创建会话、消息或模型调用，也不会暴露 OSS 凭据。")
public final class AiConversationAttachmentController {

    private final AiConversationAttachmentService attachmentService;

    public AiConversationAttachmentController(
            AiConversationAttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping("/preuploads")
    @Operation(
            summary = "批量创建会话附件预上传",
            description = "单文件最大 100 MB、单条消息最多八个文件且总计不超过 200 MB；"
                    + "客户端必须使用返回的 PUT 方法和请求头直接上传到 OSS。")
    public ResponseEntity<AiConversationPreuploadResponse> createPreuploads(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody AiConversationPreuploadRequest request) {
        var result = attachmentService.createPreuploads(
                principal.userId(),
                principal.publicId(),
                request.files().stream()
                        .map(file -> new AiConversationPreuploadFile(
                                file.fileName(),
                                file.contentType(),
                                Long.parseLong(file.sizeBytes())))
                        .toList());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(AiConversationPreuploadResponse.from(result));
    }
}
