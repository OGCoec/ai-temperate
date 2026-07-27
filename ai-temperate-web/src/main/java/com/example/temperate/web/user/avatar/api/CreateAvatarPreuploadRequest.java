package com.example.temperate.web.user.avatar.api;

import com.example.temperate.service.user.avatar.AvatarImageFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 定义创建头像预签名上传地址时允许客户端提交的格式和文件大小。
 */
@Schema(description = "创建头像预上传请求，不接受用户 ID、Object Key、Bucket 或 URL")
public record CreateAvatarPreuploadRequest(
        @NotNull
        @Schema(description = "头像格式白名单", example = "WEBP", requiredMode = Schema.RequiredMode.REQUIRED)
        AvatarImageFormat format,
        @Min(1)
        @Max(5L * 1024L * 1024L)
        @Schema(description = "客户端读取到的原始文件字节数，最大 5 MB", example = "428716")
        long sizeBytes) {
}
