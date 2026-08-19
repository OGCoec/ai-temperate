package com.example.temperate.web.user.apikey;

import java.util.Objects;

/**
 * 该值对象是来同时承载已经规范校验的 API Key ULID 和对应 16 字节内部主键。
 */
public record ApiKeyPublicId(String encoded, byte[] internalValue) {

    public ApiKeyPublicId {
        Objects.requireNonNull(encoded, "encoded must not be null");
        internalValue = Objects.requireNonNull(internalValue, "internalValue must not be null")
                .clone();
        int aggregate = 0;
        for (byte current : internalValue) {
            aggregate |= current;
        }
        if (internalValue.length != 16 || aggregate == 0) {
            throw new IllegalArgumentException("API Key internal ID must contain 16 bytes");
        }
    }

    @Override
    public byte[] internalValue() {
        return internalValue.clone();
    }
}
