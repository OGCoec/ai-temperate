package com.example.temperate.service.admin.mailinspection.rabbit;

import reactor.core.publisher.Mono;

/**
 * 定义派发完成Marker的持久发布边界，Marker确认成功后才允许ACK对应Submission Chunk。
 */
public interface MailInspectionDispatchMarkerPublisher {

    Mono<Void> publish(MailInspectionDispatchMarkerMessage message);
}
