package com.example.temperate.service.admin.mailinspection.security;

import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;

/**
 * 计算邮箱检查创建请求的不可逆HMAC指纹，禁止保存或记录参与计算的原始凭证。
 */
public interface MailInspectionRequestFingerprinter {

    MailInspectionRequestFingerprint fingerprint(
            MailInspectionType type,
            AdminMailInspectionCreateCommand command);
}
