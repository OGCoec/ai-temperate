package com.example.temperate.service.admin.mailinspection.domain;

import java.util.List;
import java.util.Objects;

/**
 * 承载创建邮箱检查任务的原始行集合，并通过防御性复制和固定脱敏文本限制凭证扩散。
 */
public record AdminMailInspectionCreateCommand(
        String clientRequestId,
        List<String> credentialLines,
        int businessConcurrency) {

    private static final String LEGACY_TEST_REQUEST_ID =
            "00000000-0000-4000-8000-000000000000";

    public AdminMailInspectionCreateCommand(
            List<String> credentialLines) {
        this(LEGACY_TEST_REQUEST_ID, credentialLines, 4);
    }

    public AdminMailInspectionCreateCommand(
            List<String> credentialLines,
            int businessConcurrency) {
        this(LEGACY_TEST_REQUEST_ID, credentialLines, businessConcurrency);
    }

    public AdminMailInspectionCreateCommand {
        Objects.requireNonNull(clientRequestId, "clientRequestId must not be null");
        Objects.requireNonNull(credentialLines, "credentialLines must not be null");
        credentialLines = List.copyOf(credentialLines);
        if (businessConcurrency < 1 || businessConcurrency > 64) {
            throw new IllegalArgumentException(
                    "businessConcurrency must be between 1 and 64");
        }
    }

    @Override
    public String toString() {
        return "AdminMailInspectionCreateCommand[clientRequestId=protected,credentialLines=protected,count="
                + credentialLines.size()
                + ",businessConcurrency="
                + businessConcurrency
                + "]";
    }
}
