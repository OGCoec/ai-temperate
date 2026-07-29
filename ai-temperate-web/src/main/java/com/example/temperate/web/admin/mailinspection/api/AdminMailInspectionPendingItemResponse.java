package com.example.temperate.web.admin.mailinspection.api;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionPendingItem;

/**
 * 返回重启后仍保存在 RabbitMQ 中的脱敏待处理行，不包含 clientId、密码、refresh token 或完整凭证。
 */
public record AdminMailInspectionPendingItemResponse(
        int lineNumber,
        String maskedEmail) {

    public static AdminMailInspectionPendingItemResponse from(
            MailInspectionPendingItem item) {
        return new AdminMailInspectionPendingItemResponse(
                item.lineNumber(),
                item.maskedEmail());
    }
}
