package com.example.temperate.service.user.apiresponse;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.apiresponse.upstream.ApiResponseSseFrame;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该结果是来在严格校验后显式区分 Responses JSON 与 SSE 两种返回模式，使 Controller 不读取请求体猜测传输类型。
 */
public sealed interface ApiResponseCreation
        permits ApiResponseCreation.Stream, ApiResponseCreation.Json {

    /** 该分支承载保留原生 event 名称的 Responses SSE 帧流。 */
    record Stream(Mono<HttpStream> response) implements ApiResponseCreation {
        public Stream {
            Objects.requireNonNull(response);
        }

        public Stream(Flux<ApiResponseSseFrame> body) {
            this(Mono.just(new HttpStream(
                    body, ApiInferenceUpstreamHeaders.empty())));
        }
    }

    /** 该分支承载仅在 Usage 结算成功后才完成的非流式原始 JSON。 */
    record Json(Mono<HttpJson> response) implements ApiResponseCreation {
        public Json {
            Objects.requireNonNull(response);
        }

        public Json(JsonNode body) {
            this(Mono.just(new HttpJson(
                    body, ApiInferenceUpstreamHeaders.empty())));
        }
    }

    /** 该值对象绑定 Responses SSE 帧与允许公开的上游响应头。 */
    record HttpStream(
            Flux<ApiResponseSseFrame> body,
            ApiInferenceUpstreamHeaders headers) {
        public HttpStream {
            Objects.requireNonNull(body);
            Objects.requireNonNull(headers);
        }
    }

    /** 该值对象绑定 Responses JSON 与允许公开的上游响应头。 */
    record HttpJson(
            JsonNode body,
            ApiInferenceUpstreamHeaders headers) {
        public HttpJson {
            Objects.requireNonNull(body);
            Objects.requireNonNull(headers);
        }
    }
}
