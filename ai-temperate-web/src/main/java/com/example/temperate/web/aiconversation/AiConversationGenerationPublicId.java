package com.example.temperate.web.aiconversation;

import java.util.Objects;

/**
 * 表示已经由统一 HybridBase64UrlCodec 校验并解码的 22 字符 Generation 公共 ID。
 */
public record AiConversationGenerationPublicId(
        String encoded,
        byte[] internalValue) {

    public AiConversationGenerationPublicId {
        Objects.requireNonNull(encoded);
        internalValue = Objects.requireNonNull(internalValue).clone();
    }

    @Override
    public byte[] internalValue() {
        return internalValue.clone();
    }
}
