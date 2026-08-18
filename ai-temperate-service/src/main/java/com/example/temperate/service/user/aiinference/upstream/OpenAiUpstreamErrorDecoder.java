package com.example.temperate.service.user.aiinference.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/**
 * 该服务是来判定上游非成功响应能否作为安全 OpenAI error envelope 公开，不能证明安全时返回受控网关错误。
 */
public interface OpenAiUpstreamErrorDecoder {

    Mono<? extends Throwable> decode(
            ClientResponse response,
            ApiInferenceUpstreamRequest request);
}
