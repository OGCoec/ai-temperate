package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassification;
import com.example.temperate.service.user.aiconversation.diagnostic.AiConversationStreamFailureClassifier;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.exception.AiConversationStreamFailureReason;

/**
 * 集中转换 Anthropic 与 Google 原生客户端失败，确保不同策略沿用相同的安全分类和公开错误边界。
 */
final class NativeProviderStreamingFailures {

    private NativeProviderStreamingFailures() {
    }

    static Throwable map(
            Throwable failure,
            AiConversationStreamFailureClassifier classifier,
            String providerLabel) {
        if (failure instanceof AiConversationException) {
            return failure;
        }
        AiConversationStreamFailureClassification classification =
                classifier.classify(failure);
        AiConversationStreamFailureReason reason = classification.reason();
        AiConversationErrorCode code = switch (reason) {
            case UPSTREAM_TOTAL_TIMEOUT -> AiConversationErrorCode.AI_UPSTREAM_TIMEOUT;
            case UPSTREAM_REASONING_LEVEL_UNSUPPORTED ->
                    AiConversationErrorCode.AI_MODEL_REASONING_LEVEL_UNSUPPORTED;
            case UPSTREAM_IMAGE_RESOLUTION_UNSUPPORTED ->
                    AiConversationErrorCode.AI_IMAGE_RESOLUTION_UNSUPPORTED;
            case UPSTREAM_TOOL_CONFIGURATION_UNSUPPORTED ->
                    AiConversationErrorCode.AI_PROVIDER_TOOL_UNSUPPORTED;
            case UPSTREAM_RATE_LIMITED,
                    UPSTREAM_AUTH_UNAVAILABLE,
                    UPSTREAM_SERVER_ERROR ->
                    AiConversationErrorCode.AI_UPSTREAM_UNAVAILABLE;
            default -> AiConversationErrorCode.AI_UPSTREAM_STREAM_FAILED;
        };
        boolean retryable = switch (code) {
            case AI_MODEL_REASONING_LEVEL_UNSUPPORTED,
                    AI_IMAGE_RESOLUTION_UNSUPPORTED,
                    AI_PROVIDER_TOOL_UNSUPPORTED -> false;
            default -> true;
        };
        String message = switch (code) {
            case AI_UPSTREAM_TIMEOUT -> providerLabel + " 模型响应超时";
            case AI_UPSTREAM_UNAVAILABLE -> providerLabel + " 模型服务暂时不可用";
            case AI_MODEL_REASONING_LEVEL_UNSUPPORTED ->
                    "当前模型不支持所选推理档位";
            case AI_IMAGE_RESOLUTION_UNSUPPORTED ->
                    "当前模型不支持所选图片分辨率";
            case AI_PROVIDER_TOOL_UNSUPPORTED ->
                    "当前模型不支持所选联网搜索配置";
            default -> providerLabel + " 模型响应未能完成";
        };
        return new AiConversationException(
                code, message, retryable, reason, failure);
    }
}
