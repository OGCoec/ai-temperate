package com.example.temperate.service.user.avatar;

/**
 * 表示头像真实解码后的格式与像素尺寸。
 */
public record AvatarImageMetadata(
        AvatarImageFormat format,
        int width,
        int height) {
}
