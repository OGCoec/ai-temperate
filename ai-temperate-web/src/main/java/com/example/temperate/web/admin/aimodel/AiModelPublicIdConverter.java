package com.example.temperate.web.admin.aimodel;

import com.example.temperate.common.codec.id.PublicIdCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 使用项目统一 PublicIdCodec 将 AI 模型 PathVariable 转换为已校验公共 ID 值对象。
 *
 * <p>转换阶段校验长度、字符、正数和规范回编码；Controller 不直接执行 Base64URL 编解码。</p>
 */
@Component
public final class AiModelPublicIdConverter implements Converter<String, AiModelPublicId> {

    private final PublicIdCodec publicIdCodec;

    public AiModelPublicIdConverter(PublicIdCodec publicIdCodec) {
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public AiModelPublicId convert(String source) {
        publicIdCodec.decode(source);
        return new AiModelPublicId(source);
    }
}
