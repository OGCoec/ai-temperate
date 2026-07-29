package com.example.temperate.service.admin.mailinspection.event;

/**
 * 定义 Redis Pub/Sub 中用于唤醒 SSE 的邮件任务变更类型，事件本身不承担历史存储。
 */
public enum MailInspectionJobEventType {
    PROGRESS,
    RESULT,
    STATUS,
    TERMINAL
}
