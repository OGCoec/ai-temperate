package com.example.temperate.service.admin.mailinspection.strategy;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResultStatus;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import com.example.temperate.service.admin.mailinspection.imap.Ip2LocationRegistrationMessageMatcher;
import com.example.temperate.service.admin.mailinspection.imap.MailboxScanOutcome;
import com.example.temperate.service.admin.mailinspection.imap.MicrosoftMailboxImapClient;
import com.example.temperate.service.admin.mailinspection.oauth.MicrosoftMailboxOAuthClient;
import org.springframework.stereotype.Component;

/**
 * 检查 IP2Location 注册邮件是否存在，并返回旧项目兼容的 clientId 与 registered 字段。
 */
@Component("ip2LocationRegistrationInspectionStrategy")
public final class Ip2LocationRegistrationInspectionStrategy
        extends AbstractMailInspectionStrategy {

    public Ip2LocationRegistrationInspectionStrategy(
            MicrosoftMailboxOAuthClient oauthClient,
            MicrosoftMailboxImapClient imapClient,
            AdminMailInspectionProperties properties) {
        super(
                oauthClient,
                imapClient,
                properties,
                new Ip2LocationRegistrationMessageMatcher(
                        properties.matchers().ip2location().senderDomain(),
                        properties.matchers().ip2location().subjectKeyword()),
                properties.scan().ip2FetchCount(),
                properties.scan().ip2MaxCandidates());
    }

    @Override
    public MailInspectionType type() {
        return MailInspectionType.IP2LOCATION_REGISTRATION;
    }

    @Override
    protected MailInspectionResult classify(
            MailboxCredential credential,
            int oauthAttempts,
            MailboxScanOutcome scan) {
        return businessResult(
                credential,
                scan.mailFound()
                        ? MailInspectionResultStatus.IP2_REGISTRATION_MAIL_FOUND
                        : MailInspectionResultStatus.IP2_REGISTRATION_MAIL_NOT_FOUND,
                scan.mailFound()
                        ? "ip2_registration_mail_found"
                        : "ip2_registration_mail_not_found",
                oauthAttempts,
                scan,
                scan.mailFound());
    }

    @Override
    protected String clientIdForResult(MailboxCredential credential) {
        return credential.clientId();
    }
}
