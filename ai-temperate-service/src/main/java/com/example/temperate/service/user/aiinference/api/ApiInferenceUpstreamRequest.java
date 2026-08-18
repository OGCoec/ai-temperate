package com.example.temperate.service.user.aiinference.api;

/**
 * 该值对象是来向 8317 调用边界传递已校验客户端请求 ID 和错误透传资格，不携带客户端凭据或请求正文。
 */
public record ApiInferenceUpstreamRequest(
        String clientRequestId,
        boolean allowOpenAiErrorPassThrough) {
}
