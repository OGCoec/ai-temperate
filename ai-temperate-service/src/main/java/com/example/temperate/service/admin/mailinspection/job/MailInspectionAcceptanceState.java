package com.example.temperate.service.admin.mailinspection.job;

/**
 * 表示某一种邮箱检查任务当前是否允许创建，用于把启动恢复失败隔离在单一检查类型内。
 */
public enum MailInspectionAcceptanceState {
    RECOVERING,
    ACCEPTING,
    UNAVAILABLE,
    STOPPED
}
