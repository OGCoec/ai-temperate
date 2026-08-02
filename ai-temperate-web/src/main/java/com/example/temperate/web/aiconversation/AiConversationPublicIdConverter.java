package com.example.temperate.web.aiconversation;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 在 Spring PathVariable 转换边界完成会话公共 ID 的长度、字符和规范回编码校验。
 */
@Component
public final class AiConversationPublicIdConverter
        implements Converter<String, AiConversationPublicId> {

    private final HybridBase64UrlCodec codec;

    public AiConversationPublicIdConverter(HybridBase64UrlCodec codec) {
        this.codec = Objects.requireNonNull(codec);
    }

    @Override
    public AiConversationPublicId convert(String source) {
        return new AiConversationPublicId(source, codec.decode(source));
    }
}
