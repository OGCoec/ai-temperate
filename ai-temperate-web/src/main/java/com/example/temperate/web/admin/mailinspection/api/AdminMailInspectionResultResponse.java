package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 返回单行邮箱检查状态和最小邮件证据，省略不适用于当前业务类型的空字段。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminMailInspectionResultResponse(
        int lineNumber,
        @Schema(description = "规范化邮箱；无有效邮箱的输入错误可为空。")
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
        @Schema(
                description = "仅 IP2Location 结果返回的规范 clientId。",
                example = "00000000-0000-0000-0000-000000000000")
        String clientId,
        Boolean registered,
        @Schema(
                description = "仅 IP2Location 链接成功时返回的完整官方验证 URL。",
                example = "https://www.ip2location.io/verify?code=<redacted>")
        String verifyUrl,
        @Schema(
                description = "仅 IP2Location 链接成功时返回的独立验证 Token。",
                example = "<redacted>")
        String verifyToken) {

    public static AdminMailInspectionResultResponse from(
            MailInspectionResult result) {
        return new AdminMailInspectionResultResponse(
                result.lineNumber(),
                result.email(),
                result.status(),
                result.failureStage(),
                result.reason(),
                result.oauthAttempts(),
                result.imapAttempts(),
                result.retryable(),
                result.retryExhausted(),
                result.mailFound(),
                result.folderName(),
                result.sender(),
                result.subject(),
                result.receivedAt(),
                result.evidencePhrase(),
                result.imapRoute(),
                result.clientId(),
                result.registered(),
                result.verifyUrl(),
                result.verifyToken());
    }

    @Override
    public String toString() {
        return "AdminMailInspectionResultResponse[lineNumber="
                + lineNumber
                + ",status="
                + status
                + ",evidence=protected]";
    }
}
