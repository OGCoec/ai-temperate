package com.example.temperate.service.user.apiresponse.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseEvent;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * 该结果是来绑定 Responses 原始 SSE 事件流与已筛选响应头，客户端可见事件名和 data 不在 HTTP 客户端层改写。
 */
public record ApiResponseUpstreamStream(
        Flux<ApiInferenceSseEvent> body,
        ApiInferenceUpstreamHeaders headers) {

    public ApiResponseUpstreamStream {
        Objects.requireNonNull(body);
        Objects.requireNonNull(headers);
    }
}
