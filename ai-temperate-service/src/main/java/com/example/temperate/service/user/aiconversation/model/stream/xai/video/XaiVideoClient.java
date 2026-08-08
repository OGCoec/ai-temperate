package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import reactor.core.publisher.Mono;

/**
 * 定义 xAI 视频异步任务的单次创建和单次状态查询；调用方负责协议轮询且不得自动重试创建请求。
 */
public interface XaiVideoClient {

    Mono<XaiVideoStartResult> start(XaiVideoStartRequest request);

    Mono<XaiVideoPollResult> poll(String requestId);
}
