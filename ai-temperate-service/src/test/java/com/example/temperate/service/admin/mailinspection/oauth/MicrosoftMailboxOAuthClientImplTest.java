package com.example.temperate.service.admin.mailinspection.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证邮箱 OAuth 客户端的三次有限重试、永久错误单次请求和响应脱敏行为。
 */
final class MicrosoftMailboxOAuthClientImplTest {

    private static final MailboxCredential CREDENTIAL = new MailboxCredential(
            1,
            "owner@example.test",
            "11111111-1111-1111-1111-111111111111",
            "refresh-secret");

    @Test
    void retriesTransientFailureAtMostThreeAttempts() {
        AtomicInteger calls = new AtomicInteger();
        OAuthTokenRequester requester = request -> {
            int attempt = calls.incrementAndGet();
            return Mono.just(attempt < 3
                    ? OAuthTokenHttpResponse.error(503, null, List.of(), null)
                    : OAuthTokenHttpResponse.success("access-secret"));
        };
        MicrosoftMailboxOAuthClient client = new MicrosoftMailboxOAuthClientImpl(
                AdminMailInspectionProperties.defaults(),
                requester,
                ignored -> Duration.ZERO);

        StepVerifier.create(client.exchange(CREDENTIAL))
                .assertNext(outcome -> {
                    assertThat(outcome.successful()).isTrue();
                    assertThat(outcome.attempts()).isEqualTo(3);
                    assertThat(outcome.toString()).doesNotContain("access-secret");
                })
                .verifyComplete();
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryPermanentFailure() {
        AtomicInteger calls = new AtomicInteger();
        OAuthTokenRequester requester = request -> {
            calls.incrementAndGet();
            return Mono.just(OAuthTokenHttpResponse.error(
                    400, "invalid_client", List.of(700016), null));
        };
        MicrosoftMailboxOAuthClient client = new MicrosoftMailboxOAuthClientImpl(
                AdminMailInspectionProperties.defaults(),
                requester,
                ignored -> Duration.ZERO);

        StepVerifier.create(client.exchange(CREDENTIAL))
                .assertNext(outcome -> {
                    assertThat(outcome.status())
                            .isEqualTo(MailInspectionResultStatus.OAUTH_CLIENT_INVALID);
                    assertThat(outcome.retryable()).isFalse();
                    assertThat(outcome.attempts()).isEqualTo(1);
                })
                .verifyComplete();
        assertThat(calls).hasValue(1);
    }

    @Test
    void marksRetryExhaustedAfterThirdRateLimitResponse() {
        OAuthTokenRequester requester = request -> Mono.just(
                OAuthTokenHttpResponse.error(429, null, List.of(), 1L));
        MicrosoftMailboxOAuthClient client = new MicrosoftMailboxOAuthClientImpl(
                AdminMailInspectionProperties.defaults(),
                requester,
                ignored -> Duration.ZERO);

        StepVerifier.create(client.exchange(CREDENTIAL))
                .assertNext(outcome -> {
                    assertThat(outcome.status())
                            .isEqualTo(MailInspectionResultStatus.OAUTH_RATE_LIMIT_EXHAUSTED);
                    assertThat(outcome.retryable()).isTrue();
                    assertThat(outcome.retryExhausted()).isTrue();
                    assertThat(outcome.attempts()).isEqualTo(3);
                })
                .verifyComplete();
    }
}
