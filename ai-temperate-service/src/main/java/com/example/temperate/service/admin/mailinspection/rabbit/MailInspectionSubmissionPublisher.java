package com.example.temperate.service.admin.mailinspection.rabbit;

import reactor.core.publisher.Mono;

/**
 * 定义加密Submission Chunk的持久发布边界，只有Broker Confirm ACK且未Return才算成功。
 */
public interface MailInspectionSubmissionPublisher {

    Mono<Void> publish(MailInspectionSubmissionChunkMessage message);
}
