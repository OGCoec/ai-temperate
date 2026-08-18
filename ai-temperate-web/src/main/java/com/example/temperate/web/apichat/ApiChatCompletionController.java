package com.example.temperate.web.apichat;

import com.example.temperate.service.user.aiinference.api.ApiInferenceClientRequestId;
import com.example.temperate.service.user.apichat.ApiChatCompletionCreation;
import com.example.temperate.service.user.apichat.ApiChatCompletionService;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticStage;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatStreamDiagnostic;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 该 Controller 是来提供 OpenAI Chat Completions 动态 JSON/SSE 入口，只编排原始 JSON、API Key 主体、安全头和无缓存响应。
 */
@RestController
@RequestMapping("/v1")
@Tag(
        name = "开放接口-Chat Completions",
        description = "供 OpenAI SDK、Agent、curl 与服务端应用调用的无状态 Chat Completions 宽松兼容入口。"
                + "仅接受 Worker 验签和 Bearer API Key；支持 JSON/SSE、函数工具、结构化输出和受能力门控的输入，"
                + "普通模式静默过滤未知或厂商不支持字段，且不负责持久化、媒体输出或持续托管资源。")
public class ApiChatCompletionController {

    private final ApiChatCompletionService completionService;

    public ApiChatCompletionController(ApiChatCompletionService completionService) {
        this.completionService = Objects.requireNonNull(completionService);
    }

    @PostMapping(
            path = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "创建 Chat Completion",
            description = "Authorization 使用脱敏 Bearer sk-***。stream 缺省或 false 返回 JSON，"
                    + "stream=true 返回 SSE；服务端为结算强制获取 Usage，但只在客户端请求时输出 Usage chunk。"
                    + "兼容开关开启时未知请求字段按普通宽松或受控透传模式处理。",
            security = @SecurityRequirement(name = "apiKeyBearer"))
    @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.HTTP_CONTROLLER)
    public Mono<ResponseEntity<?>> create(
            @AuthenticationPrincipal(errorOnInvalidType = true) ApiKeyPrincipal principal,
            @RequestHeader(
                    value = ApiInferenceClientRequestId.HEADER_NAME,
                    required = false) String clientRequestId,
            @RequestBody ObjectNode request) {
        String validatedRequestId = ApiInferenceClientRequestId.validate(clientRequestId);
        ApiChatCompletionCreation creation = completionService.create(
                principal, request, validatedRequestId);
        if (creation instanceof ApiChatCompletionCreation.Stream stream) {
            return stream.response().map(result -> {
                HttpHeaders headers = responseHeaders(
                        result.headers().toHttpHeaders(), MediaType.TEXT_EVENT_STREAM);
                Flux<ServerSentEvent<String>> body = result.body()
                        .map(data -> ServerSentEvent.builder(data).build());
                return new ResponseEntity<>(body, headers,
                        org.springframework.http.HttpStatus.OK);
            });
        }
        ApiChatCompletionCreation.Json json = (ApiChatCompletionCreation.Json) creation;
        return json.response().map(result -> new ResponseEntity<>(
                result.body(),
                responseHeaders(
                        result.headers().toHttpHeaders(), MediaType.APPLICATION_JSON),
                org.springframework.http.HttpStatus.OK));
    }

    private static HttpHeaders responseHeaders(
            HttpHeaders safeUpstreamHeaders,
            MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(safeUpstreamHeaders);
        headers.setContentType(contentType);
        headers.setCacheControl(CacheControl.noStore().cachePrivate().noTransform());
        headers.set("CDN-Cache-Control", "no-store");
        if (MediaType.TEXT_EVENT_STREAM.equals(contentType)) {
            headers.set("X-Accel-Buffering", "no");
        }
        return headers;
    }
}
