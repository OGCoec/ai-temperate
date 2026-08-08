package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

/**
 * 使用 CLIProxyAPI 兼容端点执行一次 xAI 视频 POST 或 GET，并对每次 JSON 响应设置严格内存上限。
 *
 * <p>本实现没有 retry 操作符；创建请求网络结果不确定时直接向上抛出，由计费终态进入人工对账。</p>
 */
@Service
public final class XaiVideoClientImpl implements XaiVideoClient {

    private final WebClient client;
    private final XaiVideoResponseMapper responseMapper;
    private final String pollPath;

    public XaiVideoClientImpl(
            WebClient.Builder webClientBuilder,
            AiInferenceProperties inferenceProperties,
            AiConversationVideoGenerationProperties videoProperties,
            XaiVideoResponseMapper responseMapper) {
        Objects.requireNonNull(inferenceProperties);
        Objects.requireNonNull(videoProperties);
        this.responseMapper = Objects.requireNonNull(responseMapper);
        this.pollPath = videoProperties.endpoints().poll();
        this.client = Objects.requireNonNull(webClientBuilder).clone()
                .baseUrl(inferenceProperties.baseUrl())
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + inferenceProperties.apiKey())
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(videoProperties.maximumResponseJsonBytes()))
                .build();
    }

    @Override
    public Mono<XaiVideoStartResult> start(XaiVideoStartRequest request) {
        Objects.requireNonNull(request);
        return client.post()
                .uri(request.path())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request.body())
                .exchangeToMono(response -> decodeJson(
                        response,
                        AiConversationErrorCode.AI_VIDEO_XAI_REJECTED))
                .onErrorMap(
                        WebClientRequestException.class,
                        failure -> new AiConversationException(
                                definitelyNotSubmitted(failure)
                                        ? AiConversationErrorCode.AI_VIDEO_XAI_REJECTED
                                        : AiConversationErrorCode.AI_VIDEO_XAI_RESULT_UNCERTAIN,
                                "xAI 视频创建请求结果无法确认。",
                                true,
                                failure))
                .map(responseMapper::mapStart)
                // 服务器可能已经受理了创建请求；2xx 畸形正文也不能按“未发送”直接退款。
                .onErrorMap(
                        failure -> !(failure instanceof AiConversationException),
                        failure -> uncertain(
                                "xAI 视频创建响应无法确认。", failure));
    }

    @Override
    public Mono<XaiVideoPollResult> poll(String requestId) {
        XaiVideoStartResult validated = new XaiVideoStartResult(requestId);
        return client.get()
                .uri(builder -> builder
                        .path(pollPath)
                        .build(validated.requestId()))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> decodeJson(
                        response,
                        AiConversationErrorCode.AI_VIDEO_XAI_RESULT_UNCERTAIN))
                .onErrorMap(
                        WebClientRequestException.class,
                        failure -> uncertain(
                                "xAI 视频轮询结果无法确认。", failure))
                .map(responseMapper::mapPoll)
                .map(result -> responseMapper.bindRequestId(
                        result, validated.requestId()))
                .onErrorMap(
                        failure -> !(failure instanceof AiConversationException),
                        failure -> uncertain(
                                "xAI 视频轮询响应无法确认。", failure));
    }

    private Mono<JsonNode> decodeJson(
            ClientResponse response,
            AiConversationErrorCode failureCode) {
        if (!response.statusCode().is2xxSuccessful()) {
            // 非成功正文可能包含敏感上游信息；这里只释放正文并返回稳定错误，不记录请求或响应内容。
            return response.releaseBody().then(Mono.error(
                    new AiConversationException(
                            failureCode,
                            "xAI 视频服务暂时无法完成请求。",
                            response.statusCode().value() == 429
                                    || response.statusCode().is5xxServerError())));
        }
        return response.bodyToMono(JsonNode.class)
                .switchIfEmpty(Mono.error(new AiConversationException(
                        AiConversationErrorCode.AI_VIDEO_XAI_RESULT_UNCERTAIN,
                        "xAI 视频服务返回了空响应。",
                        true)));
	}

    private static AiConversationException uncertain(
            String message,
            Throwable failure) {
        return new AiConversationException(
                AiConversationErrorCode.AI_VIDEO_XAI_RESULT_UNCERTAIN,
                message,
                true,
                failure);
    }

	private static boolean definitelyNotSubmitted(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof java.net.UnknownHostException
					|| current instanceof java.net.ConnectException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}
}
