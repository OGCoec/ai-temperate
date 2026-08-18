package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该客户端是来以服务端凭据调用 8317 的 `/v1/responses`，分别保留原生 SSE 事件和完整 JSON 响应。
 */
public interface ApiResponseUpstreamClient {

    Flux<ApiInferenceSseEvent> stream(ObjectNode payload);

    Mono<JsonNode> create(ObjectNode payload);
}
