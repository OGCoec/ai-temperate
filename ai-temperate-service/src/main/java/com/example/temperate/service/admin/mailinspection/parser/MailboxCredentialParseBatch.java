package com.example.temperate.service.admin.mailinspection.parser;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.util.List;

/**
 * 汇集可执行凭证与无需外部 I/O 的输入结果，使任务协调器不必保存原始请求行。
 */
public record MailboxCredentialParseBatch(
        int requestedCount,
        List<MailboxCredential> credentials,
        List<MailInspectionResult> immediateResults,
        int duplicateCount,
        int invalidCount) {

    public MailboxCredentialParseBatch {
        credentials = List.copyOf(credentials);
        immediateResults = List.copyOf(immediateResults);
    }
}
