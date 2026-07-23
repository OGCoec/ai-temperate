package com.example.temperate.service.registration.verification.delivery.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Endpoint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureCategory;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureHint;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.FailureStage;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.Operation;
import com.example.temperate.service.registration.verification.delivery.logging.VerificationDeliveryProviderMetadata.RecommendedAction;
import org.junit.jupiter.api.Test;

/**
 * 验证供应商诊断元数据只接受受控分类、有界退避时间和安全诊断字符串，并保持旧构造方式兼容。
 */
class VerificationDeliveryProviderMetadataTest {

    @Test
    void legacyConstructorKeepsNewDiagnosticsUnavailable() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        403,
                        "Authorization_RequestDenied",
                        "failed",
                        false,
                        "graph-request-403",
                        "ODataError");

        assertThat(metadata.operation()).isNull();
        assertThat(metadata.endpoint()).isNull();
        assertThat(metadata.failureStage()).isNull();
        assertThat(metadata.failureCategory()).isNull();
        assertThat(metadata.failureHint()).isNull();
        assertThat(metadata.recommendedAction()).isNull();
        assertThat(metadata.explicitFrom()).isNull();
        assertThat(metadata.authRefreshAttempted()).isNull();
        assertThat(metadata.retryAfterSeconds()).isNull();
    }

    @Test
    void detailedConstructorRetainsOnlyControlledDiagnostics() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        404,
                        "ErrorInvalidUser",
                        "failed",
                        false,
                        "graph-request-404",
                        "ODataError",
                        Operation.SEND_MAIL,
                        Endpoint.ME_SEND_MAIL,
                        FailureStage.PROVIDER_API,
                        FailureCategory.IDENTITY_RESOLUTION,
                        FailureHint.GRAPH_RESOURCE_NOT_RESOLVED,
                        RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER,
                        false,
                        false,
                        60L);

        assertThat(metadata.operation()).isEqualTo(Operation.SEND_MAIL);
        assertThat(metadata.endpoint()).isEqualTo(Endpoint.ME_SEND_MAIL);
        assertThat(metadata.failureStage()).isEqualTo(FailureStage.PROVIDER_API);
        assertThat(metadata.failureCategory())
                .isEqualTo(FailureCategory.IDENTITY_RESOLUTION);
        assertThat(metadata.failureHint())
                .isEqualTo(FailureHint.GRAPH_RESOURCE_NOT_RESOLVED);
        assertThat(metadata.recommendedAction())
                .isEqualTo(RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER);
        assertThat(metadata.explicitFrom()).isFalse();
        assertThat(metadata.authRefreshAttempted()).isFalse();
        assertThat(metadata.retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void retryAfterOutsideSafeRangeIsDiscarded() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                        429,
                        "TooManyRequests",
                        "failed",
                        false,
                        null,
                        "ODataError",
                        Operation.SEND_MAIL,
                        Endpoint.ME_SEND_MAIL,
                        FailureStage.PROVIDER_API,
                        FailureCategory.THROTTLED,
                        FailureHint.PROVIDER_RATE_LIMITED,
                        RecommendedAction.RETRY_AFTER_PROVIDER_DELAY,
                        false,
                        false,
                        86_401L);

        assertThat(metadata.retryAfterSeconds()).isNull();
    }

    @Test
    void fallbackExceptionClassPreservesDetailedDiagnostics() {
        VerificationDeliveryProviderMetadata metadata =
                new VerificationDeliveryProviderMetadata(
                                404,
                                "ErrorInvalidUser",
                                "failed",
                                false,
                                null,
                                null,
                                Operation.SEND_MAIL,
                                Endpoint.ME_SEND_MAIL,
                                FailureStage.PROVIDER_API,
                                FailureCategory.IDENTITY_RESOLUTION,
                                FailureHint.GRAPH_RESOURCE_NOT_RESOLVED,
                                RecommendedAction.VERIFY_AUTHENTICATED_GRAPH_USER,
                                false,
                                false,
                                null)
                        .withFallbackExceptionClass("ODataError");

        assertThat(metadata.exceptionClass()).isEqualTo("ODataError");
        assertThat(metadata.failureCategory())
                .isEqualTo(FailureCategory.IDENTITY_RESOLUTION);
        assertThat(metadata.failureHint())
                .isEqualTo(FailureHint.GRAPH_RESOURCE_NOT_RESOLVED);
    }
}
