package com.example.temperate.service.registration.verification.delivery.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.common.aliyun.AliyunUtils;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * 验证阿里云短信失败分类只允许明确的瞬态故障进入 RabbitMQ 延迟重试。
 */
class AliyunSmsFailureClassifierTest {

    private final AliyunSmsFailureClassifier classifier = new AliyunSmsFailureClassifier();

    @Test
    void frequencyAndInvalidParameterResponsesAreNonRetryable() {
        assertThat(classifier.classifyResult(result(200, "biz.FREQUENCY")).retryable())
                .isFalse();
        assertThat(classifier.classifyResult(result(200, "isv.INVALID_PARAMETERS")).retryable())
                .isFalse();
        assertThat(classifier.classifyResult(result(429, "THROTTLED")).retryable())
                .isFalse();
        assertThat(classifier.classifyResult(result(403, "isv.SMS_TEMPLATE_ILLEGAL")).retryable())
                .isFalse();
    }

    @Test
    void providerSystemFailureAndHttpFiveHundredAreRetryable() {
        assertThat(classifier.classifyResult(result(200, "isp.SYSTEM_ERROR")).retryable())
                .isTrue();
        assertThat(classifier.classifyResult(result(503, "unavailable")).retryable())
                .isTrue();
    }

    @Test
    void connectionDnsTlsAndProtocolNegotiationFailuresAreRetryable() {
        assertThat(classifier.classifyFailure(new ConnectException()).retryable()).isTrue();
        assertThat(classifier.classifyFailure(new UnknownHostException()).retryable()).isTrue();
        assertThat(classifier.classifyFailure(new SSLHandshakeException("handshake")).retryable())
                .isTrue();
        assertThat(classifier.classifyFailure(new ProtocolNegotiationException()).retryable())
                .isTrue();
    }

    @Test
    void responseTimeoutIsOutcomeUnknownAndDoesNotRetry() {
        AliyunSmsFailureClassifier.FailureDecision decision =
                classifier.classifyFailure(new SocketTimeoutException("read timed out"));

        assertThat(decision.retryable()).isFalse();
        assertThat(decision.safeReason()).isEqualTo("sms_delivery_outcome_unknown");
    }

    @Test
    void explicitConnectTimeoutIsRetryable() {
        assertThat(classifier
                        .classifyFailure(new SocketTimeoutException("connect timed out"))
                        .retryable())
                .isTrue();
    }

    @Test
    void emptySuccessfulHttpResponseIsOutcomeUnknownAndDoesNotRetry() {
        AliyunSmsFailureClassifier.FailureDecision decision = classifier.classifyResult(
                new AliyunUtils.SmsSendResult(false, 200, null, null, null));

        assertThat(decision.retryable()).isFalse();
        assertThat(decision.safeReason()).isEqualTo("sms_delivery_outcome_unknown");
    }

    private static AliyunUtils.SmsSendResult result(int httpStatus, String providerCode) {
        return new AliyunUtils.SmsSendResult(
                false, httpStatus, providerCode, false, "request-id");
    }

    private static final class ProtocolNegotiationException extends RuntimeException {
    }
}
