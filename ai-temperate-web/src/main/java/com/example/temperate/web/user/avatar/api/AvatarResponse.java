package com.example.temperate.web.user.avatar.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 定义头像确认成功后返回给当前用户的公开头像 URL。
 */
@Schema(description = "已激活头像")
public record AvatarResponse(
        @Schema(
                description = "已提交数据库并可公开读取的头像 URL",
                example = "https://ihaveaplan.oss-us-west-1.aliyuncs.com/ai-temperate/user/AAAAAAAAJxE/0123456789_abcdefghijklm.webp")
        String avatarUrl) {
}
