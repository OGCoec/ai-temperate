package com.example.temperate.service.admin.mailinspection.event;

import java.util.function.Consumer;

/**
 * 向进程内 SSE 注册表分发 Redis Pub/Sub 唤醒通知，不缓存或重放历史事件。
 */
public interface MailInspectionJobEventSubscriber {

    AutoCloseable subscribe(Consumer<MailInspectionJobEvent> listener);
}
