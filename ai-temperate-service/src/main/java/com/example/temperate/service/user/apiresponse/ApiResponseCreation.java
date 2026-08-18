package com.example.temperate.service.user.apiresponse;

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
    record Stream(Flux<ApiResponseSseFrame> body) implements ApiResponseCreation {
        public Stream {
            Objects.requireNonNull(body);
        }
    }

    /** 该分支承载仅在 Usage 结算成功后才完成的非流式原始 JSON。 */
    record Json(Mono<JsonNode> body) implements ApiResponseCreation {
        public Json {
            Objects.requireNonNull(body);
        }
    }
}
