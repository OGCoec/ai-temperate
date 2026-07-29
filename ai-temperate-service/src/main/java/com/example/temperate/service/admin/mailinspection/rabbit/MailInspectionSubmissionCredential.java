package com.example.temperate.service.admin.mailinspection.rabbit;

import java.util.Objects;

/**
 * 表示Submission Chunk内的一条最小凭证，密码在构造该类型之前已经被永久丢弃。
 */
public record MailInspectionSubmissionCredential(
        int lineNumber,
        String email,
        String clientId,
        String refreshToken) {

    public MailInspectionSubmissionCredential {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be positive");
        }
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "MailInspectionSubmissionCredential[lineNumber="
                + lineNumber
                + ",protected]";
    }
}
