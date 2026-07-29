package com.example.temperate.service.admin.mailinspection.event;

/**
 * 发布邮件任务变更唤醒通知；发布失败不得撤销已经写入 Redis 的权威状态。
 */
public interface MailInspectionJobEventPublisher {

    void publish(MailInspectionJobEvent event);
}
