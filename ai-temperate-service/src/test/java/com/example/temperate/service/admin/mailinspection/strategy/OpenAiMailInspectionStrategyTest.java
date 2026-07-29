package com.example.temperate.service.admin.mailinspection.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.imap.MailboxScanOutcome;
import com.example.temperate.service.admin.mailinspection.imap.MicrosoftMailboxImapClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthOutcome;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * 验证 OpenAI 策略只在 OAuth 与 IMAP 完成后分类限制证据，失败不会误判为未注册。
 */
final class OpenAiMailInspectionStrategyTest {

    private static final MailboxCredential CREDENTIAL = new MailboxCredential(
            1,
            "owner@example.test",
            "11111111-1111-1111-1111-111111111111",
            "refresh");

    @Test
    void classifiesRestrictedEvidence() {
        MicrosoftMailboxOAuthClient oauth = ignored -> Mono.just(
                MicrosoftMailboxOAuthOutcome.success("access", 1));
        MicrosoftMailboxImapClient imap = ignored -> Mono.just(
                MailboxScanOutcome.success(
                        1,
                        true,
                        "INBOX",
                        "openai",
                        "account notice",
                        null,
                        "deactivated",
                        "SOCKS 127.0.0.1:7897",
                        null,
                        null,
                        false));
        OpenAiMailInspectionStrategy strategy = new OpenAiMailInspectionStrategy(
                oauth, imap, AdminMailInspectionProperties.defaults());

        StepVerifier.create(strategy.inspect(CREDENTIAL))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(
                            MailInspectionResultStatus.OPENAI_RESTRICTED_EVIDENCE_FOUND);
                    assertThat(result.oauthAttempts()).isEqualTo(1);
                    assertThat(result.imapAttempts()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void preservesOauthFailureInsteadOfReturningNoEvidence() {
        MicrosoftMailboxOAuthClient oauth = ignored -> Mono.just(
                MicrosoftMailboxOAuthOutcome.failure(
                        MailInspectionResultStatus.REFRESH_TOKEN_EXPIRED,
                        "refresh_token_expired",
                        1,
                        false,
                        false));
        MicrosoftMailboxImapClient imap =
                ignored -> Mono.error(new AssertionError("IMAP must not run"));
        OpenAiMailInspectionStrategy strategy = new OpenAiMailInspectionStrategy(
                oauth, imap, AdminMailInspectionProperties.defaults());

        StepVerifier.create(strategy.inspect(CREDENTIAL))
                .assertNext(result -> assertThat(result.status())
                        .isEqualTo(MailInspectionResultStatus.REFRESH_TOKEN_EXPIRED))
                .verifyComplete();
    }
}
