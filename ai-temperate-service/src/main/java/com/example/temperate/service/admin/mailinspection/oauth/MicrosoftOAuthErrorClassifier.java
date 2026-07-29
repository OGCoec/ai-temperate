package com.example.temperate.service.admin.mailinspection.oauth;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 仅使用 Microsoft OAuth 稳定 error、error_codes 与 HTTP 状态分类，不读取或传播错误描述。
 */
public final class MicrosoftOAuthErrorClassifier {

    private static final Set<Integer> EXPIRED_CODES = Set.of(700082, 700084, 70043);
    private static final Set<Integer> REVOKED_CODES = Set.of(70000, 700080);
    private static final Set<Integer> CLIENT_INVALID_CODES = Set.of(700016, 7000215);
    private static final Set<Integer> CONSENT_CODES = Set.of(65001, 65004);
    private static final Set<Integer> ACCOUNT_RESTRICTED_CODES =
            Set.of(50053, 50055, 50057);
    private static final Set<Integer> CLIENT_TOKEN_MISMATCH_CODES =
            Set.of(700027, 7000218);

    private MicrosoftOAuthErrorClassifier() {
    }

    /**
     * 先按可重试 HTTP 边界分类，再按数字码细分永久失败，最后回退到安全的授权失败。
     */
    public static MailInspectionResultStatus classify(
            int httpStatus,
            String error,
            List<Integer> errorCodes) {
        if (httpStatus == 429) {
            return MailInspectionResultStatus.OAUTH_RATE_LIMIT_EXHAUSTED;
        }
        if (httpStatus == 408 || httpStatus >= 500) {
            return MailInspectionResultStatus.OAUTH_TRANSIENT_EXHAUSTED;
        }

        List<Integer> codes = errorCodes == null ? List.of() : errorCodes;
        if (containsAny(codes, EXPIRED_CODES)) {
            return MailInspectionResultStatus.REFRESH_TOKEN_EXPIRED;
        }
        if (containsAny(codes, REVOKED_CODES)) {
            return MailInspectionResultStatus.REFRESH_TOKEN_REVOKED;
        }
        if (containsAny(codes, ACCOUNT_RESTRICTED_CODES)) {
            return MailInspectionResultStatus.MICROSOFT_ACCOUNT_RESTRICTED;
        }
        if (containsAny(codes, CONSENT_CODES)) {
            return MailInspectionResultStatus.OAUTH_CONSENT_REQUIRED;
        }
        if (containsAny(codes, CLIENT_INVALID_CODES)) {
            return MailInspectionResultStatus.OAUTH_CLIENT_INVALID;
        }
        if (containsAny(codes, CLIENT_TOKEN_MISMATCH_CODES)) {
            return MailInspectionResultStatus.OAUTH_CLIENT_TOKEN_MISMATCH;
        }

        String normalized = error == null ? "" : error.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "invalid_client" -> MailInspectionResultStatus.OAUTH_CLIENT_INVALID;
            case "unauthorized_client" ->
                    MailInspectionResultStatus.OAUTH_CLIENT_TOKEN_MISMATCH;
            case "consent_required", "interaction_required", "invalid_scope" ->
                    MailInspectionResultStatus.OAUTH_CONSENT_REQUIRED;
            case "account_selection_required" ->
                    MailInspectionResultStatus.MICROSOFT_ACCOUNT_RESTRICTED;
            case "invalid_request" -> MailInspectionResultStatus.OAUTH_RESPONSE_INVALID;
            default -> MailInspectionResultStatus.OAUTH_AUTHORIZATION_FAILED;
        };
    }

    private static boolean containsAny(List<Integer> values, Set<Integer> expected) {
        return values.stream().anyMatch(expected::contains);
    }
}
