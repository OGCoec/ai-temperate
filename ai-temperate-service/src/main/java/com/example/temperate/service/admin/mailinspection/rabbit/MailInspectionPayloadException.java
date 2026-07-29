package com.example.temperate.service.admin.mailinspection.rabbit;

/**
 * 表示 RabbitMQ 邮箱凭证密文、AAD 或明文边界损坏，调用方必须将其作为毒消息处理而不能回显底层异常。
 */
public final class MailInspectionPayloadException extends RuntimeException {

    public MailInspectionPayloadException(String message, Throwable cause) {
        super(message, cause);
    }

    public MailInspectionPayloadException(String message) {
        super(message);
    }
}
