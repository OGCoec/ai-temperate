package com.example.temperate.web.admin.aimodelicon;

import java.util.Objects;

/**
 * 表示已经由 Spring Converter 完成规范校验的模型图标 Base64URL 公共 ID。
 */
public record AiModelIconPublicId(String value) {

    public AiModelIconPublicId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
