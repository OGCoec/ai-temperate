package com.example.temperate.web.aiconversation;

import java.util.Objects;

/**
 * 表示已经由统一 HybridBase64UrlCodec 校验并解码的 22 字符 AI 会话公共 ID。
 */
public record AiConversationPublicId(String encoded, byte[] internalValue) {

    public AiConversationPublicId {
        Objects.requireNonNull(encoded, "encoded");
        internalValue = Objects.requireNonNull(internalValue, "internalValue")
                .clone();
    }

    @Override
    public byte[] internalValue() {
        return internalValue.clone();
    }
}
