package com.example.temperate.service.admin.mailinspection.domain;

/**
 * 定义管理员邮箱检查任务支持的四种稳定业务类型，供策略注册与公开响应共同使用。
 */
public enum MailInspectionType {
    OPENAI_STATUS,
    KIRO_STATUS,
    IP2LOCATION_REGISTRATION,
    IP2LOCATION_VERIFY_LINK
}
