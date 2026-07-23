package com.example.temperate.service.audit.access.publisher;

import com.example.temperate.model.audit.access.AccessRequestAuditMessage;

/**
 * 定义脱敏访问审计事件的异步发布边界，调用方不依赖具体 RabbitMQ 实现。
 */
public interface AccessAuditPublisher {

    void publish(AccessRequestAuditMessage message);
}
