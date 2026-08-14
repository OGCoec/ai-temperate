package com.example.temperate.web.user.apikey;

import java.util.Objects;

/**
 * 该值对象是来承载已经由统一 PublicIdCodec 完成规范校验的 API Key 路径 ID，不向 Controller 暴露内部 BIGINT。
 */
public record ApiKeyPublicId(String value) {

    public ApiKeyPublicId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
