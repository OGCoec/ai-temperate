package com.example.temperate.service.admin.mailinspection.imap;

/**
 * 隔离 Jakarta Mail 阻塞扫描实现，调用方必须保证它只在受管虚拟线程上执行。
 */
@FunctionalInterface
interface BlockingMailboxScanner {

    MailboxScanOutcome scan(MailboxScanCommand command);
}
