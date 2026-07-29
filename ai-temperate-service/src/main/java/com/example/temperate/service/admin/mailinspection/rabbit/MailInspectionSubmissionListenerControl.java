package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import reactor.core.publisher.Mono;

/**
 * 控制持久化提交队列的单消费者监听器，确保重启恢复与管理员批准之前不会自动派发凭证。
 */
public interface MailInspectionSubmissionListenerControl {

    Mono<Void> start(MailInspectionType type);

    void stop(MailInspectionType type);

    void stopAll();
}
