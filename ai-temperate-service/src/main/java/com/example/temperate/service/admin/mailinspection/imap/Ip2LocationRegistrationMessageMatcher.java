package com.example.temperate.service.admin.mailinspection.imap;

import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.OrTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;
import java.util.Locale;

/**
 * 匹配 IP2Location 注册邮件并在首个可信候选出现时结束扫描，无需读取邮件正文。
 */
public final class Ip2LocationRegistrationMessageMatcher
        implements MailboxMessageMatcher {

    private final String senderDomain;
    private final String subjectKeyword;

    public Ip2LocationRegistrationMessageMatcher(
            String senderDomain,
            String subjectKeyword) {
        this.senderDomain = normalize(senderDomain);
        this.subjectKeyword = normalize(subjectKeyword);
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
        return false;
    }

    @Override
    public MailboxMessageMatch inspect(String subject, String body) {
        return MailboxMessageMatch.candidateOnly(true);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
