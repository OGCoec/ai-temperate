package com.example.temperate.service.user.openaicompatibility;

/**
 * 该枚举是来区分旧严格 DTO、已知字段宽松规范化和受控原文透传，防止后续 Adapter 用厂商名称猜测请求语义。
 */
public enum OpenAiRequestPayloadMode {
    STRICT_DTO,
    LOOSE_NORMALIZED,
    CONTROLLED_PASSTHROUGH;

    public boolean isCompatibilityEnabled() {
        return this != STRICT_DTO;
    }
}
