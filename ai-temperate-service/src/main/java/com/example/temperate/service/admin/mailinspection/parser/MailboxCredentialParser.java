package com.example.temperate.service.admin.mailinspection.parser;

import com.example.temperate.service.admin.AdminErrorCode;
import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 严格解析四段 Microsoft 邮箱凭证并逐行分类，不把未使用的密码带入 OAuth 或任务状态。
 */
@Component
public final class MailboxCredentialParser {

    private static final String DELIMITER = "----";
    private static final int MAX_EMAIL_CHARS = 320;
    private static final int MAX_PASSWORD_CHARS = 512;
    private static final int MAX_REFRESH_TOKEN_CHARS = 8192;

    private final AdminMailInspectionProperties properties;

    public MailboxCredentialParser(AdminMailInspectionProperties properties) {
        this.properties = properties;
    }

    /**
     * 在请求级容量校验后逐行执行固定优先级分类；只有完全有效且首次出现的邮箱可进入 OAuth。
     */
    public MailboxCredentialParseBatch parse(List<String> lines) {
        validateRequestBoundary(lines);
        List<MailboxCredential> credentials = new ArrayList<>();
        List<MailInspectionResult> immediateResults = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        int duplicates = 0;
        int invalid = 0;

        for (int index = 0; index < lines.size(); index++) {
            int lineNumber = index + 1;
            ParsedLine parsed = parseLine(lineNumber, lines.get(index));
            if (parsed.failure() != null) {
                immediateResults.add(parsed.failure());
                invalid++;
                continue;
            }
            MailboxCredential credential = parsed.credential();
            if (!seenEmails.add(credential.email())) {
                immediateResults.add(MailInspectionResult.inputFailure(
                        lineNumber,
                        credential.email(),
                        MailInspectionResultStatus.DUPLICATE_EMAIL,
                        "duplicate_email"));
                duplicates++;
                continue;
            }
            credentials.add(credential);
        }
        return new MailboxCredentialParseBatch(
                lines.size(), credentials, immediateResults, duplicates, invalid);
    }

    private void validateRequestBoundary(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            throw invalidRequest("mail inspection credential lines are empty");
        }
        long bytes = 0L;
        for (String line : lines) {
            if (line != null) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length;
            }
            if (bytes > properties.job().maxRequestBytes()) {
                throw invalidRequest("mail inspection request is too large");
            }
        }
    }

    private ParsedLine parseLine(int lineNumber, String line) {
        if (line == null
                || line.length() > properties.job().maxLineChars()) {
            return failure(
                    lineNumber,
                    null,
                    MailInspectionResultStatus.INVALID_CREDENTIAL_FORMAT,
                    "invalid_credential_format");
        }
        String[] fields = line.split(DELIMITER, -1);
        if (fields.length != 4) {
            return failure(
                    lineNumber,
                    null,
                    MailInspectionResultStatus.INVALID_CREDENTIAL_FORMAT,
                    "invalid_credential_format");
        }

        String email = fields[0].trim().toLowerCase(Locale.ROOT);
        String password = fields[1];
        String clientId = fields[2].trim().toLowerCase(Locale.ROOT);
        String refreshToken = fields[3].trim();

        if (!isEmailValid(email)) {
            return failure(
                    lineNumber,
                    null,
                    MailInspectionResultStatus.INVALID_EMAIL,
                    "invalid_email");
        }
        if (password.isBlank() || password.length() > MAX_PASSWORD_CHARS) {
            return failure(
                    lineNumber,
                    email,
                    MailInspectionResultStatus.INVALID_PASSWORD_FIELD,
                    "invalid_password_field");
        }
        if (!isCanonicalUuid(clientId)) {
            return failure(
                    lineNumber,
                    email,
                    MailInspectionResultStatus.INVALID_CLIENT_ID,
                    "invalid_client_id");
        }
        if (refreshToken.isBlank()
                || refreshToken.length() > MAX_REFRESH_TOKEN_CHARS
                || containsControlCharacter(refreshToken)) {
            return failure(
                    lineNumber,
                    email,
                    MailInspectionResultStatus.INVALID_REFRESH_TOKEN,
                    "invalid_refresh_token");
        }
        // 密码字段只证明母格式完整；完成该分支后不再保存或传递密码引用。
        return new ParsedLine(
                new MailboxCredential(lineNumber, email, clientId, refreshToken),
                null);
    }

    private static boolean isEmailValid(String value) {
        if (value.isBlank()
                || value.length() > MAX_EMAIL_CHARS
                || !value.contains("@")
                || containsControlCharacter(value)) {
            return false;
        }
        try {
            InternetAddress address = new InternetAddress(value, true);
            return value.equalsIgnoreCase(address.getAddress());
        } catch (AddressException exception) {
            return false;
        }
    }

    private static boolean isCanonicalUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private static ParsedLine failure(
            int lineNumber,
            String email,
            MailInspectionResultStatus status,
            String reason) {
        return new ParsedLine(
                null,
                MailInspectionResult.inputFailure(lineNumber, email, status, reason));
    }

    private static AdminException invalidRequest(String safeMessage) {
        return new AdminException(
                AdminErrorCode.ADMIN_MAIL_INSPECTION_INVALID_REQUEST,
                safeMessage);
    }

    /**
     * 将成功命令与输入失败结果设为互斥，避免调用方重复判断原始字段。
     */
    private record ParsedLine(
            MailboxCredential credential,
            MailInspectionResult failure) {
    }
}
