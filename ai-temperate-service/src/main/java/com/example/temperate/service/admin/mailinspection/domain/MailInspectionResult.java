package com.example.temperate.service.admin.mailinspection.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * 返回单行邮箱检查的稳定状态与最小邮件证据，不包含密码、OAuth Token 或邮件正文。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailInspectionResult(
        int lineNumber,
        String email,
        MailInspectionResultStatus status,
        MailInspectionFailureStage failureStage,
        String reason,
        int oauthAttempts,
        int imapAttempts,
        boolean retryable,
        boolean retryExhausted,
        boolean mailFound,
        String folderName,
        String sender,
        String subject,
        Instant receivedAt,
        String evidencePhrase,
        String imapRoute,
        String clientId,
        Boolean registered,
        String verifyUrl,
        String verifyToken) {

    /**
     * 为无法进入 OAuth 的输入行创建稳定失败结果，防止原始四段文本进入任务状态。
     */
    public static MailInspectionResult inputFailure(
            int lineNumber,
            String email,
            MailInspectionResultStatus status,
            String reason) {
        return new MailInspectionResult(
                lineNumber,
                email,
                status,
                MailInspectionFailureStage.INPUT,
                reason,
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Override
    public String toString() {
        return "MailInspectionResult[lineNumber="
                + lineNumber
                + ",status="
                + status
                + ",evidence=protected]";
    }
}
