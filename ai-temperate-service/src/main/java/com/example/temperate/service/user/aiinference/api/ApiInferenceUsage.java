package com.example.temperate.service.user.aiinference.api;

/**
 * 该记录是来统一承载 Chat Completions 与 Responses 的权威 Token 用量，供同一计费事务执行实际结算。
 */
public record ApiInferenceUsage(
        long inputTokens,
        long outputTokens,
        long cachedInputTokens) {

    public ApiInferenceUsage {
        if (inputTokens < 0
                || outputTokens < 0
                || cachedInputTokens < 0
                || cachedInputTokens > inputTokens) {
            throw new IllegalArgumentException("API inference Usage is invalid");
        }
    }
}
