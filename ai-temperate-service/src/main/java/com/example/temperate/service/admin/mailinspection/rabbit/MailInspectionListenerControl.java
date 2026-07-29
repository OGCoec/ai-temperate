package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import reactor.core.publisher.Mono;

/**
 * 定义邮件工作监听容器的受控生命周期，使正常创建和管理员恢复批准能够显式配置并发后再启动消费者。
 */
public interface MailInspectionListenerControl {

    Mono<Void> prepare(
            MailInspectionType type,
            int businessConcurrency);

    Mono<Void> start(
            MailInspectionType type,
            int businessConcurrency);

    void stop(MailInspectionType type);

    void stopAll();
}
