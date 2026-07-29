package com.example.temperate.service.admin.mailinspection.strategy;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.imap.KeywordEvidenceMessageMatcher;
import com.example.temperate.service.admin.mailinspection.imap.MailboxScanOutcome;
import com.example.temperate.service.admin.mailinspection.imap.MicrosoftMailboxImapClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthClient;
import org.springframework.stereotype.Component;

/**
 * 检查 OpenAI/ChatGPT 邮件并区分无注册证据、正常邮件和限制证据。
 */
@Component("openAiMailInspectionStrategy")
public final class OpenAiMailInspectionStrategy
        extends AbstractMailInspectionStrategy {

    public OpenAiMailInspectionStrategy(
            MicrosoftMailboxOAuthClient oauthClient,
            MicrosoftMailboxImapClient imapClient,
            AdminMailInspectionProperties properties) {
        super(
                oauthClient,
                imapClient,
                properties,
                new KeywordEvidenceMessageMatcher(
                        properties.matchers().openai().senderKeywords(),
                        properties.matchers().openai().subjectKeywords(),
                        properties.matchers().openai().restrictedPhrases()),
                properties.scan().statusFetchCount(),
                properties.scan().statusMaxCandidates());
    }

    @Override
    public MailInspectionType type() {
        return MailInspectionType.OPENAI_STATUS;
    }

    @Override
    protected MailInspectionResult classify(
            MailboxCredential credential,
            int oauthAttempts,
            MailboxScanOutcome scan) {
        if (!scan.mailFound()) {
            return businessResult(
                    credential,
                    MailInspectionResultStatus.OPENAI_NO_REGISTRATION_EVIDENCE,
                    "openai_registration_evidence_not_found",
                    oauthAttempts,
                    scan,
                    null);
        }
        if (scan.evidencePhrase() != null) {
            return businessResult(
                    credential,
                    MailInspectionResultStatus.OPENAI_RESTRICTED_EVIDENCE_FOUND,
                    "restricted_phrase_found",
                    oauthAttempts,
                    scan,
                    null);
        }
        if (scan.sender() == null && scan.subject() == null) {
            return businessResult(
                    credential,
                    MailInspectionResultStatus.OPENAI_UNCLASSIFIED,
                    "openai_mail_unclassified",
                    oauthAttempts,
                    scan,
                    null);
        }
        return businessResult(
                credential,
                MailInspectionResultStatus.OPENAI_REGISTERED_NORMAL,
                "openai_mail_found_no_restricted_evidence",
                oauthAttempts,
                scan,
                null);
    }

    @Override
    protected String clientIdForResult(MailboxCredential credential) {
        return null;
    }
}
