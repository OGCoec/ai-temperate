package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 表示一次 xAI 视频异步任务创建请求的官方路径和 JSON 对象，不包含认证头或视频字节。
 */
public record XaiVideoStartRequest(String path, ObjectNode body) {

    public XaiVideoStartRequest {
        if (path == null || !path.startsWith("/v1/videos/")) {
            throw new IllegalArgumentException("xAI video path is invalid.");
        }
        body = Objects.requireNonNull(body).deepCopy();
    }
}
