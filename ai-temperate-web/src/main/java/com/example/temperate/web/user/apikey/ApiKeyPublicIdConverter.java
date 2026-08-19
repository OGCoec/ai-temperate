package com.example.temperate.web.user.apikey;

import com.example.temperate.common.codec.id.HybridUlidCodec;
import java.util.Objects;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * 该转换器是来在 Controller 调用前把 API Key ULID 统一校验并解码为 16 字节内部主键。
 */
@Component
public final class ApiKeyPublicIdConverter implements Converter<String, ApiKeyPublicId> {

    private final HybridUlidCodec ulidCodec;

    public ApiKeyPublicIdConverter(HybridUlidCodec ulidCodec) {
        this.ulidCodec = Objects.requireNonNull(ulidCodec);
    }

    @Override
    public ApiKeyPublicId convert(String source) {
        return new ApiKeyPublicId(source, ulidCodec.decode(source));
    }
}
