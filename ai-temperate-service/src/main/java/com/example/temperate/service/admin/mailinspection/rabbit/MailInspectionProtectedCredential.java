package com.example.temperate.service.admin.mailinspection.rabbit;

import java.util.Objects;

/**
 * 表示工作消息在当前消费调用内解密出的最小 OAuth/IMAP 凭证，不得进入任务状态、响应或日志。
 */
public record MailInspectionProtectedCredential(
        String email,
        String clientId,
        String refreshToken) {

    public MailInspectionProtectedCredential {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "MailInspectionProtectedCredential[protected]";
    }
}
