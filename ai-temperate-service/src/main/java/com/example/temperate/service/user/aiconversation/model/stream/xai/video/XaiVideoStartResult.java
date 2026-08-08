package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

/**
 * 表示 xAI 视频创建端点返回的安全请求标识。
 */
public record XaiVideoStartResult(String requestId) {

    public XaiVideoStartResult {
        if (requestId == null
                || !requestId.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("xAI video request ID is invalid.");
        }
    }
}
