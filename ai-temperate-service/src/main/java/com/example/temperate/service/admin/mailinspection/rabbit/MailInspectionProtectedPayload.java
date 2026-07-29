package com.example.temperate.service.admin.mailinspection.rabbit;

import java.util.Objects;

/**
 * 承载 RabbitMQ 工作消息中的 AES-GCM 随机 IV 与密文，不允许调试文本展开任何加密内容。
 */
public record MailInspectionProtectedPayload(
        String iv,
        String ciphertext) {

    public MailInspectionProtectedPayload {
        Objects.requireNonNull(iv, "iv must not be null");
        Objects.requireNonNull(ciphertext, "ciphertext must not be null");
    }

    @Override
    public String toString() {
        return "MailInspectionProtectedPayload[protected]";
    }
}
