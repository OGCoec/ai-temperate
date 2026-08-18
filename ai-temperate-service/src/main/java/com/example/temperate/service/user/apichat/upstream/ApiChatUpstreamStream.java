package com.example.temperate.service.user.apichat.upstream;

import com.example.temperate.service.user.aiinference.api.ApiInferenceUpstreamHeaders;
import java.util.Objects;
import reactor.core.publisher.Flux;

/**
 * 该结果是来绑定 Chat 上游 SSE data 流与已筛选安全响应头，使 Controller 能在提交 200 前确定传输元数据。
 */
public record ApiChatUpstreamStream(
        Flux<String> body,
        ApiInferenceUpstreamHeaders headers) {

    public ApiChatUpstreamStream {
        Objects.requireNonNull(body);
        Objects.requireNonNull(headers);
    }
}
