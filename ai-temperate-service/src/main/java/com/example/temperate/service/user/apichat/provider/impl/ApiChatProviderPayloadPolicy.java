package com.example.temperate.service.user.apichat.provider.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.apichat.ApiChatException;
import com.example.temperate.service.user.apichat.ValidatedApiChatRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * 该策略辅助器是来执行厂商适配器自己的最终字段白名单和模型能力校验，防止公共 DTO 扩展后字段未经逐厂商批准便自动透传到 8317。
 */
final class ApiChatProviderPayloadPolicy {

    private ApiChatProviderPayloadPolicy() {
    }

    static ObjectNode requireAllowed(
            AiModelProvider provider,
            ValidatedApiChatRequest request,
            ObjectNode payload,
            Set<String> allowedTopLevelFields) {
        Objects.requireNonNull(provider);
        Objects.requireNonNull(request);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(allowedTopLevelFields);
        if (!provider.vendor().equalsIgnoreCase(request.model().vendor())
                || !request.model().capabilities()
                .contains(AiModelCapabilityCode.CHAT_COMPLETIONS)) {
            throw ApiChatException.invalid("Model provider capability mismatch.", "model");
        }
        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowedTopLevelFields.contains(field)) {
                throw ApiChatException.invalid(
                        "The model provider does not support this request field.",
                        field);
            }
        }
        return payload;
    }
}
