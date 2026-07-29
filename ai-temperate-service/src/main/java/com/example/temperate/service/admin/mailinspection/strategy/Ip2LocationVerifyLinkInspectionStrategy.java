package com.example.temperate.service.admin.mailinspection.strategy;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.imap.Ip2LocationVerifyLinkExtractor;
import com.example.temperate.service.admin.mailinspection.imap.Ip2LocationVerifyMessageMatcher;
import com.example.temperate.service.admin.mailinspection.imap.MailboxScanOutcome;
import com.example.temperate.service.admin.mailinspection.imap.MicrosoftMailboxImapClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthClient;
import org.springframework.stereotype.Component;

/**
 * 从 IP2Location 邮件中提取规范验证 URL 与独立 verifyToken，并区分未找到和畸形链接。
 */
@Component("ip2LocationVerifyLinkInspectionStrategy")
public final class Ip2LocationVerifyLinkInspectionStrategy
        extends AbstractMailInspectionStrategy {

    public Ip2LocationVerifyLinkInspectionStrategy(
            MicrosoftMailboxOAuthClient oauthClient,
            MicrosoftMailboxImapClient imapClient,
            AdminMailInspectionProperties properties) {
        super(
                oauthClient,
                imapClient,
                properties,
                new Ip2LocationVerifyMessageMatcher(
                        properties.matchers().ip2location().senderDomain(),
                        properties.matchers().ip2location().subjectKeyword(),
                        new Ip2LocationVerifyLinkExtractor()),
                properties.scan().ip2FetchCount(),
                properties.scan().ip2MaxCandidates());
    }

    @Override
    public MailInspectionType type() {
        return MailInspectionType.IP2LOCATION_VERIFY_LINK;
    }

    @Override
    protected MailInspectionResult classify(
            MailboxCredential credential,
            int oauthAttempts,
            MailboxScanOutcome scan) {
        if (scan.malformedVerifyUrl()) {
            return businessResult(
                    credential,
                    MailInspectionResultStatus.IP2_VERIFY_URL_MALFORMED,
                    "ip2_verify_url_malformed",
                    oauthAttempts,
                    scan,
                    null);
        }
        if (scan.verifyUrl() != null && scan.verifyToken() != null) {
            return businessResult(
                    credential,
                    MailInspectionResultStatus.IP2_VERIFY_URL_FOUND,
                    "ip2_verify_url_found",
                    oauthAttempts,
                    scan,
                    null);
        }
        return businessResult(
                credential,
                MailInspectionResultStatus.IP2_VERIFY_URL_NOT_FOUND,
                "ip2_verify_url_not_found",
                oauthAttempts,
                scan,
                null);
    }

    @Override
    protected String clientIdForResult(MailboxCredential credential) {
        return credential.clientId();
    }
}
