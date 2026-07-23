package com.example.temperate.service.registration.verification.delivery.util.twilio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.service.Verification;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证 Twilio 短信工具只提交项目生成的自定义验证码，并把供应商响应映射为受控投递结果。
 *
 * <p>测试通过注入发送函数隔离真实网络，不会连接 Twilio，也不会接触 Redis、数据库或 RabbitMQ。
 */
class TwilioVerifySmsUtilTest {

    private static final String ACCOUNT_SID = "ACaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String VERIFY_SERVICE_SID = "VAbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String VERIFICATION_SID = "VEcccccccccccccccccccccccccccccccc";
    private static final String AUTH_TOKEN = "test-auth-token";
    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-19T12:00:00Z");
    private static final Clock FIXED_CLOCK =
            Clock.fixed(ACCEPTED_AT, ZoneOffset.UTC);

    @Test
    void sendsCustomCodeToNormalizedInternationalNumber() {
        AtomicReference<String> capturedServiceSid = new AtomicReference<>();
        AtomicReference<String> capturedDestination = new AtomicReference<>();
        AtomicReference<String> capturedCode = new AtomicReference<>();
        Verification response = verification(VERIFICATION_SID, "pending");
        TwilioVerifySmsUtil util = util((client, serviceSid, destination, code) -> {
            capturedServiceSid.set(serviceSid);
            capturedDestination.set(destination);
            capturedCode.set(code);
            return response;
        });

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("+44 7911 123456", "012345"))
                .block();

        assertThat(capturedServiceSid.get()).isEqualTo(VERIFY_SERVICE_SID);
        assertThat(capturedDestination.get()).isEqualTo("+447911123456");
        assertThat(capturedCode.get()).isEqualTo("012345");
        assertThat(result).isNotNull();
        assertThat(result.channel()).isEqualTo(VerificationChannel.SMS);
        assertThat(result.provider()).isEqualTo("twilio-verify");
        assertThat(result.providerMessageId()).isEqualTo(VERIFICATION_SID);
        assertThat(result.acceptedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(result.metadata().httpStatus()).isNull();
        assertThat(result.metadata().providerStatus()).isEqualTo("pending");
        assertThat(result.metadata().providerSuccess()).isTrue();
    }

    @Test
    void pendingResponseCarriesSafeMetadataAcrossBoundedElastic() {
        AtomicReference<String> providerThread = new AtomicReference<>();
        TwilioVerifySmsUtil util = util((client, serviceSid, destination, code) -> {
            providerThread.set(Thread.currentThread().getName());
            return verification(VERIFICATION_SID, "pending");
        });

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("+447911123456", "012345"))
                .block();

        assertThat(result).isNotNull();
        assertThat(providerThread.get()).contains("boundedElastic");
        assertThat(result.metadata().providerStatus()).isEqualTo("pending");
        assertThat(result.metadata().providerSuccess()).isTrue();
    }

    @Test
    void rejectsInvalidPhoneOrCodeWithoutCallingTwilio() {
        AtomicInteger calls = new AtomicInteger();
        TwilioVerifySmsUtil util = util((client, serviceSid, destination, code) -> {
            calls.incrementAndGet();
            return verification(VERIFICATION_SID, "pending");
        });

        assertInvalidRequest(
                util, new VerificationDeliveryRequest("447911123456", "012345"));
        assertInvalidRequest(
                util, new VerificationDeliveryRequest("+44123", "012345"));
        assertInvalidRequest(
                util, new VerificationDeliveryRequest("+447911123456", "12345"));

        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsMissingEnvironmentVariableAtConstructionTime() {
        Map<String, String> environment = Map.of(
                "TWILIO_ACCOUNT_SID", ACCOUNT_SID,
                "TWILIO_AUTH_TOKEN", AUTH_TOKEN);

        assertThatThrownBy(() -> new TwilioVerifySmsUtil(environment::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TWILIO_VERIFY_SERVICE_SID");
    }

    @Test
    void rejectsInvalidAccountAndServiceSidFormats() {
        assertThatThrownBy(() ->
                        new TwilioVerifySmsUtil("invalid-account", AUTH_TOKEN, VERIFY_SERVICE_SID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountSid");

        assertThatThrownBy(() ->
                        new TwilioVerifySmsUtil(ACCOUNT_SID, AUTH_TOKEN, "invalid-service"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verifyServiceSid");
    }

    @Test
    void mapsRateLimitServerAndConnectionFailuresToRetryableErrors() {
        assertApiFailureIsRetryable(new ApiException("rate limited", 429), true);
        assertApiFailureIsRetryable(new ApiException("server failure", 500), true);
        assertApiFailureIsRetryable(new ApiException("connection failure"), true);
    }

    @Test
    void mapsClientFailuresToNonRetryableErrors() {
        assertApiFailureIsRetryable(new ApiException("bad request", 400), false);
        assertApiFailureIsRetryable(new ApiException("unauthorized", 401), false);
        assertApiFailureIsRetryable(new ApiException("forbidden", 403), false);
    }

    @Test
    void apiFailureCarriesHttpStatusErrorCodeAndSafeReasonWithoutRawExceptionText() {
        ApiException apiFailure = mock(ApiException.class);
        when(apiFailure.getStatusCode()).thenReturn(429);
        when(apiFailure.getHttpStatusCode()).thenReturn(429);
        when(apiFailure.getCode()).thenReturn(20429);
        when(apiFailure.getMessage())
                .thenReturn("raw response contains +447911123456 and 012345");
        TwilioVerifySmsUtil util = util((client, serviceSid, destination, code) -> {
            throw apiFailure;
        });

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("+447911123456", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.safeReason()).isEqualTo("twilio_api_failure");
                    assertThat(exception.metadata().httpStatus()).isEqualTo(429);
                    assertThat(exception.metadata().providerCode()).isEqualTo("20429");
                    assertThat(exception.metadata().providerStatus()).isEqualTo("failed");
                    assertThat(exception.metadata().providerSuccess()).isFalse();
                    assertThat(exception.metadata().exceptionClass()).isEqualTo("ApiException");
                    assertThat(exception.metadata().toString())
                            .doesNotContain("raw response contains")
                            .doesNotContain("+447911123456")
                            .doesNotContain("012345");
                });
    }

    @Test
    void rejectsMissingVerificationSidAndUnexpectedStatus() {
        TwilioVerifySmsUtil missingSid =
                util((client, serviceSid, destination, code) -> verification("", "pending"));
        TwilioVerifySmsUtil unexpectedStatus = util(
                (client, serviceSid, destination, code) ->
                        verification(VERIFICATION_SID, "approved"));

        assertThatThrownBy(() -> sendValidRequest(missingSid))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.provider()).isEqualTo("twilio-verify");
                    assertThat(exception.safeReason())
                            .isEqualTo("twilio_invalid_verification_sid");
                });
        assertThatThrownBy(() -> sendValidRequest(unexpectedStatus))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.provider()).isEqualTo("twilio-verify");
                    assertThat(exception.safeReason())
                            .isEqualTo("twilio_unexpected_verification_status");
                });
    }

    private static TwilioVerifySmsUtil util(
            TwilioVerifySmsUtil.VerificationSender sender) {
        return new TwilioVerifySmsUtil(
                mock(TwilioRestClient.class),
                VERIFY_SERVICE_SID,
                PhoneNumberUtil.getInstance(),
                FIXED_CLOCK,
                sender);
    }

    private static Verification verification(String sid, String status) {
        Verification verification = mock(Verification.class);
        when(verification.getSid()).thenReturn(sid);
        when(verification.getStatus()).thenReturn(status);
        return verification;
    }

    private static void assertInvalidRequest(
            TwilioVerifySmsUtil util, VerificationDeliveryRequest request) {
        assertThatThrownBy(() -> util.sendVerificationCode(request).block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.provider()).isEqualTo("twilio-verify");
                    assertThat(exception.safeReason()).isEqualTo("twilio_invalid_request");
                });
    }

    private static void assertApiFailureIsRetryable(
            ApiException providerFailure, boolean expectedRetryable) {
        TwilioVerifySmsUtil util = util((client, serviceSid, destination, code) -> {
            throw providerFailure;
        });

        assertThatThrownBy(() -> sendValidRequest(util))
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isEqualTo(expectedRetryable);
                    assertThat(exception.provider()).isEqualTo("twilio-verify");
                    assertThat(exception.safeReason()).isEqualTo("twilio_api_failure");
                });
    }

    private static VerificationDeliveryResult sendValidRequest(TwilioVerifySmsUtil util) {
        return util.sendVerificationCode(
                        new VerificationDeliveryRequest("+447911123456", "012345"))
                .block();
    }

}
