package com.example.temperate.web.user.avatar.api;

import com.example.temperate.service.user.avatar.AvatarPreupload;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

/**
 * 定义前端执行阿里云 OSS 原始 PUT 上传所需的完整响应。
 */
@Schema(description = "头像预签名 PUT 上传信息")
public record AvatarPreuploadResponse(
        @Schema(description = "24 位头像 NanoID", example = "0123456789_abcdefghijklm")
        String preuploadId,
        @Schema(description = "阿里云 OSS 预签名 PUT URL")
        String uploadUrl,
        @Schema(description = "固定为 PUT", example = "PUT")
        String method,
        @Schema(description = "必须原样发送的签名请求头")
        Map<String, String> uploadHeaders,
        @Schema(description = "预签名地址失效时间")
        Instant expiresAt) {

    public static AvatarPreuploadResponse from(AvatarPreupload value) {
        return new AvatarPreuploadResponse(
                value.preuploadId(),
                value.uploadUrl(),
                value.method(),
                value.uploadHeaders(),
                value.expiresAt());
    }
}
