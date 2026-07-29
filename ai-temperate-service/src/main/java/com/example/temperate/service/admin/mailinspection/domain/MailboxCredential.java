package com.example.temperate.service.admin.mailinspection.domain;

import java.util.Objects;

/**
 * 表示已通过边界校验、可进入 OAuth 的最小邮箱凭证，不保留输入中的密码字段。
 *
 * <p>refresh token 只在待处理 Reactor 工作项中短暂存活；该对象禁止进入任务状态、日志和响应。</p>
 */
public record MailboxCredential(
        int lineNumber,
        String email,
        String clientId,
        String refreshToken) {

    public MailboxCredential {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "MailboxCredential[lineNumber="
                + lineNumber
                + ",credentials=protected]";
    }
}
