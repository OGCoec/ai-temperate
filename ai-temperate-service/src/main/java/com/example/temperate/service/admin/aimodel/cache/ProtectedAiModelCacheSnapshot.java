package com.example.temperate.service.admin.aimodel.cache;

/**
 * 承载可以写入 Redis 的 AI 模型密文信封及其加密前字节数。
 *
 * <p>明文字节数仅用于 BigKey 阈值与指标判断，不包含实际模型字段内容。</p>
 */
public record ProtectedAiModelCacheSnapshot(String envelope, int plaintextBytes) {
}
