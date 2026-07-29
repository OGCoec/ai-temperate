package com.example.temperate.service.admin.mailinspection.domain;

/**
 * 标识单条邮箱检查失败发生的稳定阶段，避免向客户端暴露第三方异常文本。
 */
public enum MailInspectionFailureStage {
    INPUT,
    OAUTH,
    IMAP,
    BUSINESS,
    MESSAGE_QUEUE,
    COORDINATOR
}
