package com.example.temperate.service.admin.mailinspection.diagnostic;

import com.example.temperate.service.admin.AdminException;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPayloadException;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPoisonMessageException;
import com.example.temperate.service.admin.mailinspection.rabbit.MailInspectionPublishException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 将邮件检查异常归入稳定低基数类别，避免把第三方消息或敏感载荷写入日志标签。
 */
@Component
public final class MailInspectionFailureClassifier {

    /**
     * 根据异常类型返回诊断类别，不读取异常消息。
     */
    public String classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DataAccessException) {
                return "redis";
            }
            if (current instanceof MailInspectionPublishException) {
                return "rabbit-publish";
            }
            if (current instanceof MailInspectionPoisonMessageException) {
                return "rabbit-poison";
            }
            if (current instanceof MailInspectionPayloadException) {
                return "payload";
            }
            if (current instanceof AdminException) {
                return "business";
            }
            current = current.getCause();
        }
        return "unexpected";
    }
}
