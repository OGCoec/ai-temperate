package com.example.temperate.service.user.apichat.upstream;

import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;

/**
 * 该客户端是来把已适配 JSON 发送到固定 8317 Chat Completions，并只返回 SSE data 字段，不暴露上游 HTTP 错误正文。
 */
public interface ApiChatUpstreamClient {

    Flux<String> stream(ObjectNode payload);
}
