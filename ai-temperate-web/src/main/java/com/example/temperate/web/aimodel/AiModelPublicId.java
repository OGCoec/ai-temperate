package com.example.temperate.web.aimodel;

import java.util.Objects;

/**
 * 表示已经由 Spring Converter 使用统一 PublicIdCodec 校验过的共享 AI 模型公共 ID。
 *
 * <p>管理员与普通用户 Controller 共同使用该值对象；它只保存规范 Base64URL 文本，不暴露解码后的
 * 数据库 BIGINT。</p>
 */
public record AiModelPublicId(String value) {

    public AiModelPublicId {
        Objects.requireNonNull(value, "value must not be null");
    }
}
