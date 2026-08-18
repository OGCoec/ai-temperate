package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import reactor.core.publisher.Mono;

/**
 * 该客户端是来以服务端凭据调用 8317 的 `/v1/responses`，分别保留原生 SSE 事件和完整 JSON 响应。
 */
public interface ApiResponseUpstreamClient {

    Mono<ApiResponseUpstreamStream> stream(
            ObjectNode payload,
            ApiInferenceUpstreamRequest request);

    Mono<ApiResponseUpstreamJson> create(
            ObjectNode payload,
            ApiInferenceUpstreamRequest request);
}
