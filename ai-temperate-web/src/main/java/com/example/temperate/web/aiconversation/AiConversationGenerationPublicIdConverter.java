package com.example.temperate.web.aiconversation;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 在 Spring PathVariable 边界校验 Generation 公共 ID 的长度、字符集和规范回编码。
 */
@Component
public final class AiConversationGenerationPublicIdConverter
        implements Converter<String, AiConversationGenerationPublicId> {

    private final HybridBase64UrlCodec codec;

    public AiConversationGenerationPublicIdConverter(
            HybridBase64UrlCodec codec) {
        this.codec = Objects.requireNonNull(codec);
    }

    @Override
    public AiConversationGenerationPublicId convert(String source) {
        return new AiConversationGenerationPublicId(source, codec.decode(source));
    }
}
