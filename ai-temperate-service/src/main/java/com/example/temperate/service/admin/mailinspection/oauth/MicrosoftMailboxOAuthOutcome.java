package com.example.temperate.service.admin.mailinspection.oauth;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;

/**
 * 表示一次 OAuth 交换的最小内存结果，成功 Token 不会被序列化、日志化或保存到任务状态。
 */
public record MicrosoftMailboxOAuthOutcome(
        String accessToken,
        MailInspectionResultStatus status,
        String reason,
        int attempts,
        boolean retryable,
        boolean retryExhausted) {

    /**
     * 只有包含非空 access token 且没有错误码时才允许进入 IMAP。
     */
    public boolean successful() {
        return accessToken != null && !accessToken.isBlank() && status == null;
    }

    public static MicrosoftMailboxOAuthOutcome success(
            String accessToken,
            int attempts) {
        return new MicrosoftMailboxOAuthOutcome(
                accessToken, null, "ok", attempts, false, false);
    }

    public static MicrosoftMailboxOAuthOutcome failure(
            MailInspectionResultStatus status,
            String reason,
            int attempts,
            boolean retryable,
            boolean retryExhausted) {
        return new MicrosoftMailboxOAuthOutcome(
                null, status, reason, attempts, retryable, retryExhausted);
    }

    @Override
    public String toString() {
        return "MicrosoftMailboxOAuthOutcome[status="
                + status
                + ",attempts="
                + attempts
                + ",token=protected]";
    }
}
