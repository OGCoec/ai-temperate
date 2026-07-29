package com.example.temperate.service.admin.mailinspection.imap;

/**
 * 表示候选邮件中可提前结束扫描的限制短语或 IP2 验证链接证据。
 */
public record MailboxMessageMatch(
        boolean terminal,
        String evidencePhrase,
        String verifyUrl,
        String verifyToken,
        boolean malformedVerifyUrl) {

    public static MailboxMessageMatch candidateOnly(boolean terminal) {
        return new MailboxMessageMatch(terminal, null, null, null, false);
    }

    public static MailboxMessageMatch restricted(String phrase) {
        return new MailboxMessageMatch(true, phrase, null, null, false);
    }

    public static MailboxMessageMatch verifyLink(
            String url,
            String token) {
        return new MailboxMessageMatch(true, null, url, token, false);
    }

    public static MailboxMessageMatch malformedVerifyLink() {
        return new MailboxMessageMatch(true, null, null, null, true);
    }

    @Override
    public String toString() {
        return "MailboxMessageMatch[terminal="
                + terminal
                + ",evidence=protected]";
    }
}
