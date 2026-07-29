package com.example.temperate.service.admin.mailinspection.recovery;

import reactor.core.publisher.Mono;

/**
 * 定义应用启动后的 RabbitMQ 邮箱任务恢复扫描边界；扫描只重建暂停快照，不允许执行任何邮箱业务调用。
 */
public interface MailInspectionRecoveryCoordinator {

    Mono<Void> recoverAll();
}
