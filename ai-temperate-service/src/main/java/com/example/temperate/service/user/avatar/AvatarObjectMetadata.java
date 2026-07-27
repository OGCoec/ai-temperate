package com.example.temperate.service.user.avatar;

/**
 * 表示确认头像前从 OSS HEAD 响应读取的安全校验字段。
 */
public record AvatarObjectMetadata(
        long sizeBytes,
        String contentType) {
}
