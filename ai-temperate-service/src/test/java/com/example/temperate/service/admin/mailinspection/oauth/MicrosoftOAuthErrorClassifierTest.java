package com.example.temperate.service.admin.mailinspection.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Microsoft OAuth 只根据稳定机器码、AADSTS 数字码和 HTTP 状态分类。
 */
final class MicrosoftOAuthErrorClassifierTest {

    @Test
    void distinguishesPermanentAuthorizationFailures() {
        assertThat(MicrosoftOAuthErrorClassifier.classify(
                        400, "invalid_grant", List.of(700082)))
                .isEqualTo(MailInspectionResultStatus.REFRESH_TOKEN_EXPIRED);
        assertThat(MicrosoftOAuthErrorClassifier.classify(
                        400, "invalid_client", List.of(700016)))
                .isEqualTo(MailInspectionResultStatus.OAUTH_CLIENT_INVALID);
        assertThat(MicrosoftOAuthErrorClassifier.classify(
                        400, "interaction_required", List.of(65001)))
                .isEqualTo(MailInspectionResultStatus.OAUTH_CONSENT_REQUIRED);
        assertThat(MicrosoftOAuthErrorClassifier.classify(
                        400, "invalid_grant", List.of(50053)))
                .isEqualTo(MailInspectionResultStatus.MICROSOFT_ACCOUNT_RESTRICTED);
    }

    @Test
    void distinguishesRetryableHttpFailures() {
        assertThat(MicrosoftOAuthErrorClassifier.classify(429, null, List.of()))
                .isEqualTo(MailInspectionResultStatus.OAUTH_RATE_LIMIT_EXHAUSTED);
        assertThat(MicrosoftOAuthErrorClassifier.classify(503, null, List.of()))
                .isEqualTo(MailInspectionResultStatus.OAUTH_TRANSIENT_EXHAUSTED);
    }
}
