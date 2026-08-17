package com.example.temperate.web.apichat;

import com.example.temperate.service.user.apichat.ApiChatCompletionService;
import com.example.temperate.service.user.apichat.ApiChatRequest;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatDiagnosticStage;
import com.example.temperate.service.user.apichat.diagnostic.ApiChatStreamDiagnostic;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 该 Controller 是来提供精确的 OpenAI Chat Completions 流式入口，只编排严格请求 DTO、专用 API Key Principal、SSE 和无缓存响应头。
 * 类型保持可代理以便诊断切面观察同步入口；实际流终态仍由 Service Reactor Context 与 Servlet 异步过滤器负责。
 */
@RestController
@RequestMapping("/v1")
@Tag(
        name = "开放接口-Chat Completions",
        description = "供 OpenAI SDK、Agent、curl 与服务端应用调用的纯流式 Chat Completions。"
                + "仅接受 Worker 验签和 Bearer API Key，不使用 H5 Cookie、Android Token 或浏览器 CORS，"
                + "不提供非流式、多模态、Responses API 或客户端包下载。")
public class ApiChatCompletionController {

    private final ApiChatCompletionService completionService;

    public ApiChatCompletionController(ApiChatCompletionService completionService) {
        this.completionService = Objects.requireNonNull(completionService);
    }

    @PostMapping(
            path = "/chat/completions",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "创建流式 Chat Completion",
            description = "Authorization 使用 Bearer sk-***。stream 必须为 true；后端始终向 8317 请求最终 Usage，"
                    + "仅在 stream_options.include_usage=true 时向客户端转发 Usage chunk。",
            security = @SecurityRequirement(name = "apiKeyBearer"))
    @ApiChatStreamDiagnostic(ApiChatDiagnosticStage.HTTP_CONTROLLER)
    public ResponseEntity<Flux<ServerSentEvent<String>>> stream(
            @AuthenticationPrincipal(errorOnInvalidType = true) ApiKeyPrincipal principal,
            @RequestBody ApiChatRequest request) {
        Flux<ServerSentEvent<String>> body = completionService.stream(principal, request)
                .map(data -> ServerSentEvent.builder(data).build());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore().cachePrivate().noTransform())
                .header("CDN-Cache-Control", "no-store")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
