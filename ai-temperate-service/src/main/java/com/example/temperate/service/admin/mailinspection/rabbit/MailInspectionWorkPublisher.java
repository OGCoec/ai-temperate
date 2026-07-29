package com.example.temperate.service.admin.mailinspection.rabbit;

import reactor.core.publisher.Mono;

/**
 * 定义邮箱检查工作消息的可靠异步发布边界，只有 broker confirm ACK 且消息未被退回时才视为成功。
 */
public interface MailInspectionWorkPublisher {

    Mono<Void> publish(MailInspectionWorkMessage message);
}
