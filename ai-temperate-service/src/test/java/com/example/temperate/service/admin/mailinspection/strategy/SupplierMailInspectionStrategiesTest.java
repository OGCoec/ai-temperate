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
 * 验证 Kiro 与两类 IP2Location 策略返回各自稳定状态和兼容证据字段。
 */
final class SupplierMailInspectionStrategiesTest {

    private static final MailboxCredential CREDENTIAL = new MailboxCredential(
            1,
            "owner@example.test",
            "11111111-1111-1111-1111-111111111111",
            "refresh");
    private static final MicrosoftMailboxOAuthClient OAUTH = ignored -> Mono.just(
            MicrosoftMailboxOAuthOutcome.success("access", 1));
    private static final AdminMailInspectionProperties PROPERTIES =
            AdminMailInspectionProperties.defaults();

    @Test
    void kiroClassifiesNoRegistrationEvidence() {
        KiroMailInspectionStrategy strategy = new KiroMailInspectionStrategy(
                OAUTH,
                ignored -> Mono.just(noMail()),
                PROPERTIES);

        StepVerifier.create(strategy.inspect(CREDENTIAL))
                .assertNext(result -> assertThat(result.status()).isEqualTo(
                        MailInspectionResultStatus.KIRO_NO_REGISTRATION_EVIDENCE))
                .verifyComplete();
    }

    @Test
    void ip2RegistrationReturnsClientIdAndBoolean() {
        MicrosoftMailboxImapClient imap = ignored -> Mono.just(
                MailboxScanOutcome.success(
                        1,
                        true,
                        "INBOX",
                        "ip2location.io",
                        "registration",
                        null,
                        null,
                        "SOCKS 127.0.0.1:7897",
                        null,
                        null,
                        false));
        Ip2LocationRegistrationInspectionStrategy strategy =
                new Ip2LocationRegistrationInspectionStrategy(
                        OAUTH, imap, PROPERTIES);

        StepVerifier.create(strategy.inspect(CREDENTIAL))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(
                            MailInspectionResultStatus.IP2_REGISTRATION_MAIL_FOUND);
                    assertThat(result.clientId()).isEqualTo(CREDENTIAL.clientId());
                    assertThat(result.registered()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void ip2VerifyReturnsUrlAndToken() {
        MicrosoftMailboxImapClient imap = ignored -> Mono.just(
                MailboxScanOutcome.success(
                        1,
                        true,
                        "INBOX",
                        "ip2location.io",
                        "verify",
                        null,
                        null,
                        "SOCKS 127.0.0.1:7897",
                        "https://www.ip2location.io/verify?code=Abc_123",
                        "Abc_123",
                        false));
        Ip2LocationVerifyLinkInspectionStrategy strategy =
                new Ip2LocationVerifyLinkInspectionStrategy(
                        OAUTH, imap, PROPERTIES);

        StepVerifier.create(strategy.inspect(CREDENTIAL))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(
                            MailInspectionResultStatus.IP2_VERIFY_URL_FOUND);
                    assertThat(result.verifyUrl()).endsWith("Abc_123");
                    assertThat(result.verifyToken()).isEqualTo("Abc_123");
                    assertThat(result.toString()).doesNotContain("Abc_123");
                })
                .verifyComplete();
    }

    private static MailboxScanOutcome noMail() {
        return MailboxScanOutcome.success(
                1,
                false,
                null,
                null,
                null,
                null,
                null,
                "SOCKS 127.0.0.1:7897",
                null,
                null,
                false);
    }
}
