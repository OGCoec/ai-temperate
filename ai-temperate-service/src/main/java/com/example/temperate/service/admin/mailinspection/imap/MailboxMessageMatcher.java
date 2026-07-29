package com.example.temperate.service.admin.mailinspection.imap;

import jakarta.mail.search.SearchTerm;

/**
 * 定义候选邮件过滤与正文证据提取边界，使 IMAP 基础设施不选择具体业务策略。
 */
public interface MailboxMessageMatcher {

    SearchTerm candidateSearchTerm();

    boolean isCandidate(String sender, String subject);

    boolean requiresBody();

    MailboxMessageMatch inspect(String subject, String body);
}
