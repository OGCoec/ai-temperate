package com.example.temperate.service.admin.mailinspection.imap;

import reactor.core.publisher.Mono;

/**
 * 将 Jakarta Mail 阻塞扫描隔离到虚拟线程，并向策略返回稳定的邮件证据或 IMAP 失败。
 */
public interface MicrosoftMailboxImapClient {

    /**
     * 按命令执行只读 IMAPS/XOAUTH2 扫描，临时网络错误最多尝试三次。
     */
    Mono<MailboxScanOutcome> scan(MailboxScanCommand command);
}
