package com.example.temperate.service.admin.mailinspection.strategy;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionFailureStage;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.imap.MailboxMessageMatcher;
import com.example.temperate.service.admin.mailinspection.imap.MailboxScanCommand;
import com.example.temperate.service.admin.mailinspection.imap.MailboxScanOutcome;
import com.example.temperate.service.admin.mailinspection.imap.MicrosoftMailboxImapClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthOutcome;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

/**
 * 统一编排 OAuth、IMAP 与 120 秒总截止，具体子类只负责构造匹配器和业务结果分类。
 */
abstract class AbstractMailInspectionStrategy
        implements MailInspectionStrategy {

    private final MicrosoftMailboxOAuthClient oauthClient;
    private final MicrosoftMailboxImapClient imapClient;
    private final AdminMailInspectionProperties properties;
    private final MailboxMessageMatcher matcher;
    private final int fetchCount;
    private final int maxCandidates;

    AbstractMailInspectionStrategy(
            MicrosoftMailboxOAuthClient oauthClient,
            MicrosoftMailboxImapClient imapClient,
            AdminMailInspectionProperties properties,
            MailboxMessageMatcher matcher,
            int fetchCount,
            int maxCandidates) {
        this.oauthClient = Objects.requireNonNull(oauthClient);
        this.imapClient = Objects.requireNonNull(imapClient);
        this.properties = Objects.requireNonNull(properties);
        this.matcher = Objects.requireNonNull(matcher);
        this.fetchCount = fetchCount;
        this.maxCandidates = maxCandidates;
    }

    @Override
    public final Mono<MailInspectionResult> inspect(
            MailboxCredential credential) {
        AtomicReference<MailInspectionFailureStage> stage =
                new AtomicReference<>(MailInspectionFailureStage.OAUTH);
        return oauthClient.exchange(credential)
                .flatMap(oauth -> {
                    if (!oauth.successful()) {
                        return Mono.just(oauthFailure(credential, oauth));
                    }
                    stage.set(MailInspectionFailureStage.IMAP);
                    MailboxScanCommand command = new MailboxScanCommand(
                            credential.email(),
                            oauth.accessToken(),
                            fetchCount,
                            maxCandidates,
                            matcher);
                    return imapClient.scan(command)
                            .map(scan -> scan.successful()
                                    ? classify(credential, oauth.attempts(), scan)
                                    : imapFailure(credential, oauth.attempts(), scan));
                })
                // 外层截止覆盖 OAuth、退避、并发等待与 IMAP 的完整单行生命周期。
                .timeout(properties.oauth().credentialTimeout())
                .onErrorResume(TimeoutException.class, ignored -> Mono.just(
                        totalTimeout(credential, stage.get())))
                .onErrorResume(failure -> Mono.just(internalFailure(credential)));
    }

    protected abstract MailInspectionResult classify(
            MailboxCredential credential,
            int oauthAttempts,
            MailboxScanOutcome scan);

    protected abstract String clientIdForResult(MailboxCredential credential);

    protected final MailInspectionResult businessResult(
            MailboxCredential credential,
            MailInspectionResultStatus status,
            String reason,
            int oauthAttempts,
            MailboxScanOutcome scan,
            Boolean registered) {
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                status,
                null,
                reason,
                oauthAttempts,
                scan.attempts(),
                false,
                false,
                scan.mailFound(),
                scan.folderName(),
                scan.sender(),
                scan.subject(),
                scan.receivedAt(),
                scan.evidencePhrase(),
                scan.imapRoute(),
                clientIdForResult(credential),
                registered,
                scan.verifyUrl(),
                scan.verifyToken());
    }

    private MailInspectionResult oauthFailure(
            MailboxCredential credential,
            MicrosoftMailboxOAuthOutcome oauth) {
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                oauth.status(),
                MailInspectionFailureStage.OAUTH,
                oauth.reason(),
                oauth.attempts(),
                0,
                oauth.retryable(),
                oauth.retryExhausted(),
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                clientIdForResult(credential),
                null,
                null,
                null);
    }

    private MailInspectionResult imapFailure(
            MailboxCredential credential,
            int oauthAttempts,
            MailboxScanOutcome scan) {
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                scan.failureStatus(),
                MailInspectionFailureStage.IMAP,
                scan.reason(),
                oauthAttempts,
                scan.attempts(),
                scan.retryable(),
                scan.retryExhausted(),
                false,
                null,
                null,
                null,
                null,
                null,
                scan.imapRoute(),
                clientIdForResult(credential),
                null,
                null,
                null);
    }

    private MailInspectionResult totalTimeout(
            MailboxCredential credential,
            MailInspectionFailureStage stage) {
        MailInspectionResultStatus status =
                stage == MailInspectionFailureStage.OAUTH
                        ? MailInspectionResultStatus.OAUTH_NETWORK_EXHAUSTED
                        : MailInspectionResultStatus.IMAP_SCAN_TIMEOUT;
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                status,
                stage,
                "credential_total_timeout",
                0,
                0,
                true,
                true,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                clientIdForResult(credential),
                null,
                null,
                null);
    }

    private MailInspectionResult internalFailure(
            MailboxCredential credential) {
        return new MailInspectionResult(
                credential.lineNumber(),
                credential.email(),
                MailInspectionResultStatus.INTERNAL_PROCESSING_FAILURE,
                MailInspectionFailureStage.COORDINATOR,
                "mail_inspection_internal_failure",
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                clientIdForResult(credential),
                null,
                null,
                null);
    }
}
