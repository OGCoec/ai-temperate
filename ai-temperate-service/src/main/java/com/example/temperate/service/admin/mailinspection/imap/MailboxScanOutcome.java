package com.example.temperate.service.admin.mailinspection.imap;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import java.time.Instant;

/**
 * 表示 IMAP 扫描的稳定证据或失败元数据，不包含 access token 与邮件正文。
 */
public record MailboxScanOutcome(
        boolean successful,
        MailInspectionResultStatus failureStatus,
        String reason,
        int attempts,
        boolean retryable,
        boolean retryExhausted,
        boolean mailFound,
        String folderName,
        String sender,
        String subject,
        Instant receivedAt,
        String evidencePhrase,
        String imapRoute,
        String verifyUrl,
        String verifyToken,
        boolean malformedVerifyUrl) {

    public static MailboxScanOutcome success(
            int attempts,
            boolean mailFound,
            String folderName,
            String sender,
            String subject,
            Instant receivedAt,
            String evidencePhrase,
            String imapRoute,
            String verifyUrl,
            String verifyToken,
            boolean malformedVerifyUrl) {
        return new MailboxScanOutcome(
                true,
                null,
                "ok",
                attempts,
                false,
                false,
                mailFound,
                folderName,
                sender,
                subject,
                receivedAt,
                evidencePhrase,
                imapRoute,
                verifyUrl,
                verifyToken,
                malformedVerifyUrl);
    }

    public static MailboxScanOutcome failure(
            MailInspectionResultStatus status,
            String reason,
            int attempts,
            boolean retryable,
            boolean retryExhausted,
            String imapRoute) {
        return new MailboxScanOutcome(
                false,
                status,
                reason,
                attempts,
                retryable,
                retryExhausted,
                false,
                null,
                null,
                null,
                null,
                null,
                imapRoute,
                null,
                null,
                false);
    }

    public MailboxScanOutcome withAttempts(int value) {
        return new MailboxScanOutcome(
                successful,
                failureStatus,
                reason,
                value,
                retryable,
                retryExhausted,
                mailFound,
                folderName,
                sender,
                subject,
                receivedAt,
                evidencePhrase,
                imapRoute,
                verifyUrl,
                verifyToken,
                malformedVerifyUrl);
    }

    @Override
    public String toString() {
        return "MailboxScanOutcome[successful="
                + successful
                + ",status="
                + failureStatus
                + ",evidence=protected]";
    }
}
