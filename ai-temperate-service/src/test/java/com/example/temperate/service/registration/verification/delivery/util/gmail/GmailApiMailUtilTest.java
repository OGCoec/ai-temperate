package com.example.temperate.service.registration.verification.delivery.util.gmail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.registration.enums.VerificationChannel;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryRequest;
import com.example.temperate.service.registration.verification.delivery.dto.VerificationDeliveryResult;
import com.example.temperate.service.registration.verification.delivery.exception.VerificationDeliveryException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * 验证 Gmail API 邮件工具只把 Gmail 接受消息视为成功，并把 HTTP 错误映射为受控投递异常。
 */
class GmailApiMailUtilTest {

    @Test
    void mapsMessageIdResponseToDeliveryResult() {
        GmailApiMailUtil util = new GmailApiMailUtil(
                "no-reply@example.test",
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                Duration.ofSeconds(2),
                () -> Mono.just("access-token"),
                request -> Mono.just(new GmailApiMailUtil.GmailMessageResponse(
                        "gmail-message-id", "thread-id")));

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block();

        assertThat(result.channel()).isEqualTo(VerificationChannel.EMAIL);
        assertThat(result.provider()).isEqualTo("gmail");
        assertThat(result.providerMessageId()).isEqualTo("gmail-message-id");
        assertThat(result.metadata().httpStatus()).isNull();
        assertThat(result.metadata().providerStatus()).isEqualTo("accepted");
        assertThat(result.metadata().providerSuccess()).isTrue();
    }

    @Test
    void rejectsBlankMessageIdAsRetryableProviderFailure() {
        GmailApiMailUtil util = new GmailApiMailUtil(
                "no-reply@example.test",
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                Duration.ofSeconds(2),
                () -> Mono.just("access-token"),
                request -> Mono.just(new GmailApiMailUtil.GmailMessageResponse("", null)));

        assertThatThrownBy(() -> util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.provider()).isEqualTo("gmail");
                });
    }

    @Test
    void invalidRequestValidationIsDeferredUntilSubscription() {
        GmailApiMailUtil util = new GmailApiMailUtil(
                "no-reply@example.test",
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                Duration.ofSeconds(2),
                () -> Mono.just("access-token"),
                request -> Mono.just(new GmailApiMailUtil.GmailMessageResponse(
                        "gmail-message-id", "thread-id")));

        Mono<VerificationDeliveryResult> operation = util.sendVerificationCode(
                new VerificationDeliveryRequest("alice@example.test", "12345"));

        assertThat(operation).isNotNull();
        assertThatThrownBy(operation::block)
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.safeReason()).isEqualTo("gmail_invalid_request");
                });
    }

    @Test
    void unauthorizedResponseInvalidatesTokenAndRetriesOnlyOnce() {
        RefreshableTokenSupplier tokenSupplier = new RefreshableTokenSupplier();
        AtomicInteger calls = new AtomicInteger();
        GmailApiMailUtil util = new GmailApiMailUtil(
                "no-reply@example.test",
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                Duration.ofSeconds(2),
                tokenSupplier,
                request -> calls.getAndIncrement() == 0
                        ? Mono.error(httpError(HttpStatus.UNAUTHORIZED))
                        : Mono.just(new GmailApiMailUtil.GmailMessageResponse(
                                "gmail-message-id", "thread-id")));

        VerificationDeliveryResult result = util.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block();

        assertThat(result.providerMessageId()).isEqualTo("gmail-message-id");
        assertThat(tokenSupplier.invalidations).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void tooManyRequestsIsRetryableAndBadRequestIsNotRetryable() {
        GmailApiMailUtil retryable = utilReturning(httpError(HttpStatus.TOO_MANY_REQUESTS));
        GmailApiMailUtil nonRetryable = utilReturning(httpError(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> retryable.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.metadata().httpStatus()).isEqualTo(429);
                    assertThat(exception.metadata().providerStatus()).isEqualTo("failed");
                    assertThat(exception.metadata().providerSuccess()).isFalse();
                });
        assertThatThrownBy(() -> nonRetryable.sendVerificationCode(
                        new VerificationDeliveryRequest("alice@example.test", "012345"))
                .block())
                .isInstanceOfSatisfying(VerificationDeliveryException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.metadata().httpStatus()).isEqualTo(400);
                    assertThat(exception.metadata().providerStatus()).isEqualTo("failed");
                    assertThat(exception.metadata().providerSuccess()).isFalse();
                });
    }

    @Test
    void productionConstructorAcceptsAWebClient() {
        GmailApiMailUtil util = new GmailApiMailUtil(
                WebClient.builder().build(),
                new GmailApiProperties(
                        "client-id",
                        "client-secret",
                        "refresh-token",
                        "no-reply@example.test",
                        "https://oauth2.googleapis.com/token",
                        "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                        Duration.ofSeconds(2)),
                () -> Mono.just("access-token"));

        assertThat(util).isNotNull();
    }

    private static GmailApiMailUtil utilReturning(Throwable failure) {
        return new GmailApiMailUtil(
                "no-reply@example.test",
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/send",
                Duration.ofSeconds(2),
                () -> Mono.just("access-token"),
                request -> Mono.error(failure));
    }

    private static WebClientResponseException httpError(HttpStatus status) {
        return WebClientResponseException.create(
                status.value(),
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
    }

    private static final class RefreshableTokenSupplier implements GmailAccessTokenSupplier {

        private int calls;
        private int invalidations;

        @Override
        public Mono<String> accessToken() {
            calls++;
            return Mono.just("access-token-" + calls);
        }

        @Override
        public void invalidate() {
            invalidations++;
        }
    }
}
