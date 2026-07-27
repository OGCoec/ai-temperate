package com.example.temperate.service.user.avatar;

import java.time.Instant;
import java.util.Map;

/**
 * 表示客户端执行原始 PUT 所需的无状态头像预上传响应。
 */
public record AvatarPreupload(
        String preuploadId,
        String uploadUrl,
        String method,
        Map<String, String> uploadHeaders,
        Instant expiresAt) {
}
