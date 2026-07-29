package com.example.temperate.service.admin.mailinspection.imap;

import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.util.Locale;

/**
 * 匹配 IP2Location 邮件并从主题与正文中提取官方验证 URL 和独立 Token。
 */
public final class Ip2LocationVerifyMessageMatcher
        implements MailboxMessageMatcher {

    private final String senderDomain;
    private final String subjectKeyword;
    private final Ip2LocationVerifyLinkExtractor extractor;

    public Ip2LocationVerifyMessageMatcher(
            String senderDomain,
            String subjectKeyword,
            Ip2LocationVerifyLinkExtractor extractor) {
        this.senderDomain = normalize(senderDomain);
        this.subjectKeyword = normalize(subjectKeyword);
        this.extractor = extractor;
    }

    @Override
    public SearchTerm candidateSearchTerm() {
        return new OrTerm(
                new FromStringTerm(senderDomain),
                new SubjectTerm(subjectKeyword));
    }

    @Override
    public boolean isCandidate(String sender, String subject) {
        return normalize(sender).contains(senderDomain)
                || normalize(subject).contains(subjectKeyword);
    }

    @Override
    public boolean requiresBody() {
        return true;
    }

    @Override
    public MailboxMessageMatch inspect(String subject, String body) {
        Ip2LocationVerifyLinkExtractor.Extraction extraction = extractor.extract(
                (subject == null ? "" : subject)
                        + "\n"
                        + (body == null ? "" : body));
        if (extraction.verifyUrl() != null) {
            return MailboxMessageMatch.verifyLink(
                    extraction.verifyUrl(), extraction.verifyToken());
        }
        if (extraction.malformed()) {
            return MailboxMessageMatch.malformedVerifyLink();
        }
        return MailboxMessageMatch.candidateOnly(false);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
