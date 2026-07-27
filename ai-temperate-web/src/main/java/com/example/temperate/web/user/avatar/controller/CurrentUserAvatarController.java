package com.example.temperate.web.user.avatar.controller;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.avatar.AvatarConfirmation;
import com.example.temperate.service.user.avatar.AvatarImageFormat;
import com.example.temperate.service.user.avatar.UserAvatarService;
import com.example.temperate.web.user.avatar.api.AvatarPreuploadResponse;
import com.example.temperate.web.user.avatar.api.AvatarResponse;
import com.example.temperate.web.user.avatar.api.ConfirmAvatarRequest;
import com.example.temperate.web.user.avatar.api.CreateAvatarPreuploadRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 为已认证普通用户提供头像预上传、取消和同步确认 HTTP API。
 *
 * <p>所有对象路径均由认证主体重建；Controller 不接收内部用户 ID、Object Key、Bucket 或正式 URL。</p>
 */
@Validated
@RestController
@RequestMapping("/api/users/me/avatar")
@Tag(
        name = "用户-当前用户头像",
        description = "供 H5 与 Android 已认证普通用户创建阿里云 OSS 私有预上传、精确取消并同步确认公开头像。"
                + "所有接口仅操作当前 Access Token 对应用户，不修改 Refresh Token、Refresh Session 或 Redis。")
public class CurrentUserAvatarController {

    private final UserAvatarService avatarService;

    public CurrentUserAvatarController(UserAvatarService avatarService) {
        this.avatarService = avatarService;
    }

    @PostMapping("/preuploads")
    @Operation(
            summary = "创建头像预签名上传",
            description = "返回十分钟有效的原始 PUT 地址；客户端必须发送响应中的 Content-Type、私有 ACL 和禁止覆盖请求头。")
    public ResponseEntity<AvatarPreuploadResponse> createPreupload(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody CreateAvatarPreuploadRequest request) {
        var preupload = avatarService.createPreupload(
                principal.userId(),
                principal.publicId(),
                request.format(),
                request.sizeBytes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(AvatarPreuploadResponse.from(preupload));
    }

    @DeleteMapping("/preuploads/{preuploadId}")
    @Operation(
            summary = "取消头像预上传",
            description = "仅删除当前用户临时目录下指定 NanoID 和格式对应的对象；对象不存在时也返回 204。")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Parameter(description = "24 位头像 NanoID")
            @Pattern(regexp = "^[A-Za-z0-9_-]{24}$")
            @PathVariable String preuploadId,
            @RequestParam AvatarImageFormat format) {
        avatarService.cancel(
                principal.userId(),
                principal.publicId(),
                preuploadId,
                format);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .build();
    }

    @PostMapping("/preuploads/{preuploadId}/confirm")
    @Operation(
            summary = "确认使用预上传头像",
            description = "同步完成 OSS 真实图片校验、服务端复制与 PostgreSQL 头像切换后返回公开 URL。")
    public ResponseEntity<AvatarResponse> confirm(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Parameter(description = "24 位头像 NanoID")
            @Pattern(regexp = "^[A-Za-z0-9_-]{24}$")
            @PathVariable String preuploadId,
            @Valid @RequestBody ConfirmAvatarRequest request) {
        AvatarConfirmation confirmation = avatarService.confirm(
                principal.userId(),
                principal.publicId(),
                preuploadId,
                request.format());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(new AvatarResponse(confirmation.avatarUrl()));
    }
}
