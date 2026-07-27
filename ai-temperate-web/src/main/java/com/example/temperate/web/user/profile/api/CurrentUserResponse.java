package com.example.temperate.web.user.profile.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 定义个人中心当前用户资料接口的最小响应，明确排除内部 ID、密码和令牌等安全字段。
 */
@Schema(description = "当前已认证用户可展示的最小个人资料")
public record CurrentUserResponse(
        @Schema(description = "用户展示名称", example = "Alice")
        String displayName,
        @Schema(description = "完整邮箱，仅返回给当前已认证用户", example = "a***@example.test")
        String email,
        @Schema(description = "E.164 手机号；未绑定时为 null", example = "+1415***0123", nullable = true)
        String phone,
        @Schema(
                description = "当前头像公开 URL；尚未设置头像时为 null",
                example = "https://ihaveaplan.oss-us-west-1.aliyuncs.com/ai-temperate/user/AAAAAAAAJxE/0123456789_abcdefghijklmnopqrstuvwxyz-.webp",
                nullable = true)
        String avatarUrl) {
}
