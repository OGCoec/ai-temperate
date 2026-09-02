package com.example.temperate.web.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 该测试是来固定六号生产回调的安全失败响应和低基数传输拒绝指标。 */
final class LiuhaoPaymentCallbackExceptionHandlerTest {

    @Test
    void transportFailureReturnsBadRequestAndCountsOnlyTheFixedReason() {
        MembershipPaymentMetrics metrics = mock(MembershipPaymentMetrics.class);
        LiuhaoPaymentCallbackExceptionHandler handler =
                new LiuhaoPaymentCallbackExceptionHandler(metrics);

        ResponseEntity<String> response = handler.handleTransport(
                new LiuhaoPaymentCallbackTransportException(
                        LiuhaoPaymentCallbackTransportException.Reason.REPEATED_PARAMETER));

        assertFail(response, HttpStatus.BAD_REQUEST);
        verify(metrics).callbackTransportRejected("repeated_parameter");
    }

    @Test
    void invalidSignatureReturnsUnauthorizedWithoutSuccessAcknowledgement() {
        LiuhaoPaymentCallbackExceptionHandler handler =
                new LiuhaoPaymentCallbackExceptionHandler(mock(MembershipPaymentMetrics.class));

        ResponseEntity<String> response = handler.handleBusiness(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID,
                "Sensitive upstream detail must not be returned."));

        assertFail(response, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unconfirmedProviderQueryReturnsBadGatewayWithoutSuccessAcknowledgement() {
        LiuhaoPaymentCallbackExceptionHandler handler =
                new LiuhaoPaymentCallbackExceptionHandler(mock(MembershipPaymentMetrics.class));

        ResponseEntity<String> response = handler.handleBusiness(new MembershipPaymentException(
                MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID,
                "Sensitive upstream detail must not be returned."));

        assertFail(response, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void unexpectedFailureReturnsServiceUnavailableWithoutSuccessAcknowledgement() {
        LiuhaoPaymentCallbackExceptionHandler handler =
                new LiuhaoPaymentCallbackExceptionHandler(mock(MembershipPaymentMetrics.class));

        ResponseEntity<String> response = handler.handleUnexpected(
                new IllegalStateException("Sensitive runtime detail must not be returned."));

        assertFail(response, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static void assertFail(ResponseEntity<String> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo("fail");
        assertThat(response.getHeaders().getContentType())
                .hasToString("text/plain;charset=UTF-8");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
    }
}
