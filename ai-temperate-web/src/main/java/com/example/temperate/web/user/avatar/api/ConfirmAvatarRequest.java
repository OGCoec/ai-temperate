package com.example.temperate.web.user.avatar.api;

import com.example.temperate.service.user.avatar.AvatarImageFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 定义确认头像时用于重建临时和正式 Object Key 的图片格式。
 */
@Schema(description = "确认使用预上传头像请求")
public record ConfirmAvatarRequest(
        @NotNull
        @Schema(description = "必须与创建预上传时一致的头像格式", example = "WEBP")
        AvatarImageFormat format) {
}
