package com.example.temperate.web.admin.aimodelicon;

import com.example.temperate.common.codec.id.PublicIdCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 将模型图标 PathVariable 转换为规范 Base64URL 公共 ID，并拒绝非规范编码。
 */
@Component
public final class AiModelIconPublicIdConverter
        implements Converter<String, AiModelIconPublicId> {

    private final PublicIdCodec publicIdCodec;

    public AiModelIconPublicIdConverter(PublicIdCodec publicIdCodec) {
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public AiModelIconPublicId convert(String source) {
        long decoded = publicIdCodec.decode(source);
        return new AiModelIconPublicId(publicIdCodec.encode(decoded));
    }
}
