package com.example.temperate.web.admin.mailinspection;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;

/**
 * 表示已由 Spring Converter 完成规范校验的 128 位邮箱检查任务公共 ID。
 *
 * <p>值对象只保留 22 字符公共文本，不暴露或派生数据库 Long。</p>
 */
public record MailInspectionJobPublicId(String value) {

    private static final HybridBase64UrlCodec CODEC =
            new HybridBase64UrlCodec();

    public MailInspectionJobPublicId {
        // 值对象自身再次锁定规范性，避免测试、反序列化或未来非 MVC 调用绕过 Converter。
        CODEC.decode(value);
    }
}
