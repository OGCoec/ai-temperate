package com.example.temperate.service.admin.mailinspection.imap;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;

/**
 * 在虚拟线程内部传递已分类 IMAP 失败，只保存稳定原因而不保存邮箱、Token 或服务器文本。
 */
final class MailboxImapFailureException extends RuntimeException {

    private final MailInspectionResultStatus status;
    private final String safeReason;
    private final boolean retryable;

    private MailboxImapFailureException(
            MailInspectionResultStatus status,
            String safeReason,
            boolean retryable,
            Throwable cause) {
        super(safeReason, cause);
        this.status = status;
        this.safeReason = safeReason;
        this.retryable = retryable;
    }

    static MailboxImapFailureException retryable(
            MailInspectionResultStatus status,
            String safeReason,
            Throwable cause) {
        return new MailboxImapFailureException(status, safeReason, true, cause);
    }

    static MailboxImapFailureException permanent(
            MailInspectionResultStatus status,
            String safeReason,
            Throwable cause) {
        return new MailboxImapFailureException(status, safeReason, false, cause);
    }

    MailInspectionResultStatus status() {
        return status;
    }

    String safeReason() {
        return safeReason;
    }

    boolean retryable() {
        return retryable;
    }
}
