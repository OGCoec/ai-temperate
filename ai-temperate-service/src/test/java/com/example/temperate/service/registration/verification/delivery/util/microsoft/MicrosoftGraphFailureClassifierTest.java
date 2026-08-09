package com.example.temperate.service.registration.verification.delivery.util.microsoft;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 验证 Microsoft Graph 状态与机器可读错误码只映射为受控诊断分类，不依赖第三方原始错误文本。
 */
class MicrosoftGraphFailureClassifierTest {

    @Test
    void classifiesInvalidUserAsAuthenticatedGraphIdentityResolutionFailure() {
        MicrosoftGraphFailureClassifier.Classification classification =
                MicrosoftGraphFailureClassifier.classify(
                        404, "ErrorInvalidUser", new IllegalStateException());

        assertThat(classification.failureStage()).isEqualTo(FailureStage.PROVIDER_API);
        assertThat(classification.failureCategory())
                .isEqualTo(FailureCategory.IDENTITY_RESOLUTION);
        assertThat(classification.failureHint())
                .isEqualTo(FailureHint.GRAPH_RESOURCE_NOT_RESOLVED);
        assertThat(classification.recommendedAction())
                .isEqualTo(RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER);
    }

    @Test
    void classifiesSendAsAndPermissionFailuresSeparately() {
        assertClassification(
                403,
                "ErrorSendAsDenied",
                FailureCategory.SENDER_AUTHORIZATION,
                FailureHint.EXPLICIT_SENDER_NOT_AUTHORIZED,
                RecommendedAction.REMOVE_OR_AUTHORIZE_EXPLICIT_SENDER);
        assertClassification(
                403,
                "Authorization_RequestDenied",
                FailureCategory.PERMISSION_DENIED,
                FailureHint.GRAPH_PERMISSION_OR_CONSENT_MISSING,
                RecommendedAction.VERIFY_MAIL_SEND_CONSENT);
        assertClassification(
                403,
                "ErrorAccessDenied",
                FailureCategory.PERMISSION_DENIED,
                FailureHint.RESOURCE_ACCESS_DENIED,
                RecommendedAction.VERIFY_ACCOUNT_PERMISSION_AND_LICENSE);
    }

    @Test
    void classifiesAuthenticationAndMailboxFailures() {
        assertClassification(
                401,
                "InvalidAuthenticationToken",
                FailureCategory.AUTHENTICATION_FAILED,
                FailureHint.TOKEN_REJECTED_AFTER_REFRESH,
                RecommendedAction.REAUTHORIZE_MICROSOFT_ACCOUNT);
        assertClassification(
                403,
                "MailboxNotEnabledForRESTAPI",
                FailureCategory.MAILBOX_UNAVAILABLE,
                FailureHint.MAILBOX_NOT_AVAILABLE_TO_GRAPH,
                RecommendedAction.VERIFY_OUTLOOK_MAILBOX_STATE);
    }

    @Test
    void classifiesHttpStatusFallbacks() {
        assertClassification(
                400,
                null,
                FailureCategory.INVALID_REQUEST,
                FailureHint.PROVIDER_REJECTED_REQUEST,
                RecommendedAction.VERIFY_GRAPH_REQUEST_CONFIGURATION);
        assertClassification(
                404,
                null,
                FailureCategory.RESOURCE_NOT_FOUND,
                FailureHint.GRAPH_RESOURCE_NOT_RESOLVED,
                RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER);
        assertClassification(
                429,
                null,
                FailureCategory.THROTTLED,
                FailureHint.PROVIDER_RATE_LIMITED,
                RecommendedAction.RETRY_AFTER_PROVIDER_DELAY);
        assertClassification(
                503,
                null,
                FailureCategory.TRANSIENT_PROVIDER_FAILURE,
                FailureHint.PROVIDER_TEMPORARILY_UNAVAILABLE,
                RecommendedAction.RETRY_WITH_BACKOFF);
    }

    @Test
    void classifiesTimeoutTransportAndUnknownFailures() {
        MicrosoftGraphFailureClassifier.Classification timeout =
                MicrosoftGraphFailureClassifier.classify(
                        null, null, new TimeoutException());
        MicrosoftGraphFailureClassifier.Classification transport =
                MicrosoftGraphFailureClassifier.classify(
                        null, null, new IllegalStateException(new IOException()));
        MicrosoftGraphFailureClassifier.Classification unknown =
                MicrosoftGraphFailureClassifier.classify(
                        null, null, new IllegalStateException());

        assertThat(timeout.failureStage()).isEqualTo(FailureStage.TIMEOUT);
        assertThat(timeout.failureCategory()).isEqualTo(FailureCategory.TIMEOUT);
        assertThat(transport.failureStage()).isEqualTo(FailureStage.TRANSPORT);
        assertThat(transport.failureCategory())
                .isEqualTo(FailureCategory.TRANSPORT_FAILURE);
        assertThat(unknown.failureCategory())
                .isEqualTo(FailureCategory.UNCLASSIFIED_PROVIDER_ERROR);
    }

    @Test
    void classifiesOauthExchangeWithoutReadingOauthResponseBody() {
        MicrosoftGraphFailureClassifier.Classification rejected =
                MicrosoftGraphFailureClassifier.classifyOAuth(
                        400, new IllegalStateException("raw oauth response"));
        MicrosoftGraphFailureClassifier.Classification throttled =
                MicrosoftGraphFailureClassifier.classifyOAuth(
                        429, new IllegalStateException("raw oauth response"));

        assertThat(rejected.failureStage()).isEqualTo(FailureStage.AUTHENTICATION);
        assertThat(rejected.failureCategory())
                .isEqualTo(FailureCategory.AUTHENTICATION_FAILED);
        assertThat(rejected.failureHint())
                .isEqualTo(FailureHint.OAUTH_CREDENTIAL_OR_REFRESH_TOKEN_REJECTED);
        assertThat(rejected.recommendedAction())
                .isEqualTo(RecommendedAction.REAUTHORIZE_OR_VERIFY_CLIENT_CREDENTIALS);
        assertThat(throttled.failureCategory()).isEqualTo(FailureCategory.THROTTLED);
    }

    @Test
    void mapsMicrosoftOauthMachineCodeToSafeFailureReason() {
        assertThat(MicrosoftGraphFailureClassifier.oauthFailureReason(
                        "invalid_grant", List.of(700084)))
                .isEqualTo("spa_refresh_token_expired");
        assertThat(MicrosoftGraphFailureClassifier.oauthFailureReason(
                        "invalid_client", List.of(70002)))
                .isEqualTo("client_secret_invalid");
        assertThat(MicrosoftGraphFailureClassifier.oauthFailureReason(
                        "invalid_grant", List.of()))
                .isEqualTo("oauth_invalid_grant");
    }

    private static void assertClassification(
            Integer httpStatus,
            String providerCode,
            FailureCategory expectedCategory,
            FailureHint expectedHint,
            RecommendedAction expectedAction) {
        MicrosoftGraphFailureClassifier.Classification classification =
                MicrosoftGraphFailureClassifier.classify(
                        httpStatus, providerCode, new IllegalStateException());

        assertThat(classification.failureCategory()).isEqualTo(expectedCategory);
        assertThat(classification.failureHint()).isEqualTo(expectedHint);
        assertThat(classification.recommendedAction()).isEqualTo(expectedAction);
    }
}
