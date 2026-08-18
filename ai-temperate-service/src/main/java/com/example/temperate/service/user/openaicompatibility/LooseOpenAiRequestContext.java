package com.example.temperate.service.user.openaicompatibility;

import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

/**
 * 该上下文是来把原始请求、已解析模型和公开协议交给规范化策略，同时保持认证主体不进入字段转换边界。
 */
public record LooseOpenAiRequestContext(
        ObjectNode rawPayload,
        AiModelCacheEntry model,
        OpenAiCompatibilityProtocol protocol) {

    public LooseOpenAiRequestContext {
        rawPayload = Objects.requireNonNull(rawPayload).deepCopy();
        model = Objects.requireNonNull(model);
        protocol = Objects.requireNonNull(protocol);
    }

    @Override
    public ObjectNode rawPayload() {
        return rawPayload.deepCopy();
    }
}
