package com.example.temperate.service.admin.mailinspection.rabbit;

/**
 * 表示邮箱检查工作消息在有限重试后仍未获得可靠发布确认，异常不包含消息密文或邮箱凭证。
 */
public final class MailInspectionPublishException extends RuntimeException {

    public MailInspectionPublishException(String message) {
        super(message);
    }

    public MailInspectionPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
