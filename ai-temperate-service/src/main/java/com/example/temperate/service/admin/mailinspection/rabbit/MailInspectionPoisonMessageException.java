package com.example.temperate.service.admin.mailinspection.rabbit;

/**
 * 表示邮箱检查工作消息信封与固定队列契约不一致，消费者必须拒绝并送入死信队列而不能继续外部调用。
 */
public final class MailInspectionPoisonMessageException
        extends RuntimeException {

    public MailInspectionPoisonMessageException(String message) {
        super(message);
    }
}
