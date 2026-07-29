package com.example.temperate.service.admin.mailinspection.imap;

import java.util.Objects;

/**
 * 承载一次只读 IMAP 扫描的邮箱、短期 access token、候选上限和业务匹配器。
 */
public record MailboxScanCommand(
        String email,
        String accessToken,
        int fetchCount,
        int maxCandidates,
        MailboxMessageMatcher matcher) {

    public MailboxScanCommand {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(matcher, "matcher must not be null");
        if (fetchCount < 1 || maxCandidates < 1) {
            throw new IllegalArgumentException("scan limits must be positive");
        }
    }

    @Override
    public String toString() {
        return "MailboxScanCommand[mailboxAndToken=protected,fetchCount="
                + fetchCount
                + ",maxCandidates="
                + maxCandidates
                + "]";
    }
}
