package com.example.temperate.web.admin.aimodel;

import java.util.Objects;

/**
 * 表示已经由 Spring Converter 使用统一 PublicIdCodec 校验过的 AI 模型公共 ID。
 *
 * <p>该值对象只保存规范 Base64URL 文本，不暴露解码后的数据库 BIGINT。</p>
 */
public record AiModelPublicId(String value) {

    public AiModelPublicId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
