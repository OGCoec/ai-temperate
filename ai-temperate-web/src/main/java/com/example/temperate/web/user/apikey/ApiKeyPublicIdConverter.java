package com.example.temperate.web.user.apikey;

import com.example.temperate.common.codec.id.PublicIdCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 该转换器是来在 Controller 调用前统一校验 API Key PathVariable 的长度、字符、正数和规范回编码。
 */
@Component
public final class ApiKeyPublicIdConverter implements Converter<String, ApiKeyPublicId> {

    private final PublicIdCodec publicIdCodec;

    public ApiKeyPublicIdConverter(PublicIdCodec publicIdCodec) {
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
    }

    @Override
    public ApiKeyPublicId convert(String source) {
        publicIdCodec.decode(source);
        return new ApiKeyPublicId(source);
    }
}
