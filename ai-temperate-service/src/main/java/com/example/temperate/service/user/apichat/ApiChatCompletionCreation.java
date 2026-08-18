package com.example.temperate.service.user.apichat;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该结果是来显式区分 Chat JSON 与 SSE，并让 Controller 在提交 HTTP 200 前取得安全上游响应头。
 */
public sealed interface ApiChatCompletionCreation
        permits ApiChatCompletionCreation.Stream, ApiChatCompletionCreation.Json {

    /** 该分支在上游响应头验证完成后提供保持字段完整的 SSE data 流。 */
    record Stream(Mono<HttpStream> response) implements ApiChatCompletionCreation {
        public Stream {
            Objects.requireNonNull(response);
        }
    }

    /** 该分支只在原始 JSON Usage 已完成本地结算后产生 HTTP 成功结果。 */
    record Json(Mono<HttpJson> response) implements ApiChatCompletionCreation {
        public Json {
            Objects.requireNonNull(response);
        }
    }

    /** 该值对象绑定 SSE body 与白名单响应头。 */
    record HttpStream(
            Flux<String> body,
            ApiInferenceUpstreamHeaders headers) {
        public HttpStream {
            Objects.requireNonNull(body);
            Objects.requireNonNull(headers);
        }
    }

    /** 该值对象绑定 JSON body 与白名单响应头。 */
    record HttpJson(
            JsonNode body,
            ApiInferenceUpstreamHeaders headers) {
        public HttpJson {
            Objects.requireNonNull(body);
            Objects.requireNonNull(headers);
        }
    }
}
