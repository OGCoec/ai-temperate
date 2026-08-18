package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;

/**
 * 该结果是来冻结 Responses 请求的规范模型、流模式、有效输出上限和输入估算，后续上游与计费阶段不得重新解释客户端字段。
 */
public record ValidatedApiResponseRequest(
        ApiResponseRequest request,
        AiModelCacheEntry model,
        long effectiveMaxOutputTokens,
        long estimatedInputTokens,
        boolean stream) {
}
