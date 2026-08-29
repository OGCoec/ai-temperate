package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * 该 Controller 是来为 W16 专用回环实例提供最小 OpenAI/xAI 协议替身，使正式 Controller 与计费链可以在不连接模型供应商时完成验收。
 *
 * <p>入口同时要求 loadtest-bar Profile、独立显式开关、Servlet 回环来源和 CLIProxy Bearer 凭据；普通公网实例默认不注册，且响应不回显 Prompt 或凭据。</p>
 */
@RestController
@RequestMapping(MembershipPaymentLoadtestRequestPolicy.INFERENCE_STUB_ROOT)
@Profile("loadtest-bar")
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest.inference-stub",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测推理替身",
        description = "仅供服务器第二回环实例执行 W16 首次额度周期验收；返回最小推理协议，不提供公网模型能力。")
public final class MembershipPaymentLoadtestInferenceStubController {

    private static final String VIDEO_REQUEST_ID = "loadtest-video-request";
    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2n0QAAAAASUVORK5CYII=");

    private final MembershipPaymentLoadtestInferenceStubProperties properties;
    private final AiInferenceProperties inferenceProperties;
    private final ObjectMapper objectMapper;

    public MembershipPaymentLoadtestInferenceStubController(
            MembershipPaymentLoadtestInferenceStubProperties properties,
            AiInferenceProperties inferenceProperties,
            ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties);
        this.inferenceProperties = Objects.requireNonNull(inferenceProperties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @PostMapping("/v1/chat/completions")
    @Operation(summary = "返回最小 Chat Completions JSON 或 SSE")
    public ResponseEntity<?> chat(
            @RequestBody ObjectNode body,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest request) {
        requireAuthorized(authorization, request);
        if (body != null && body.path("stream").asBoolean(false)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(chatEvents());
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(chatCompletion());
    }

    @PostMapping({"/v1/images/generations", "/v1/images/edits"})
    @Operation(summary = "返回一张最小 PNG 与权威 Token 用量事件")
    public ResponseEntity<?> image(
            @RequestBody ObjectNode ignoredBody,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest request) {
        requireAuthorized(authorization, request);
        String accept = Objects.toString(request.getHeader(HttpHeaders.ACCEPT), "");
        if (accept.contains(MediaType.APPLICATION_JSON_VALUE)
                && !accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("request_id", "loadtest-image-request");
            response.putArray("data").addObject()
                    .put("id", "loadtest-image")
                    .put("b64_json", Base64.getEncoder().encodeToString(PNG_BYTES));
            response.putObject("usage").put("cost_in_usd_ticks", 1L);
            return json(response);
        }
        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "image_generation.completed");
        event.put("id", "loadtest-image-request");
        event.put("b64_json", Base64.getEncoder().encodeToString(PNG_BYTES));
        event.put("size", "1x1");
        event.put("output_format", "png");
        ObjectNode usage = event.putObject("usage");
        usage.put("total_tokens", 2);
        usage.put("input_tokens", 1);
        usage.put("output_tokens", 1);
        usage.putObject("input_tokens_details").put("cached_tokens", 0);
        usage.putObject("output_tokens_details").put("reasoning_tokens", 0);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(Flux.just(ServerSentEvent.builder(writeJson(event))
                        .event("image_generation.completed")
                        .build()));
    }

    @PostMapping({
        "/v1/videos/generations",
        "/v1/videos/edits",
        "/v1/videos/extensions"
    })
    @Operation(summary = "接受一条最小视频生成任务")
    public ResponseEntity<ObjectNode> startVideo(
            @RequestBody ObjectNode ignoredBody,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest request) {
        requireAuthorized(authorization, request);
        return json(objectMapper.createObjectNode()
                .put("request_id", VIDEO_REQUEST_ID));
    }

    @GetMapping("/v1/videos/{requestId}")
    @Operation(summary = "返回最小视频任务完成事实")
    public ResponseEntity<ObjectNode> pollVideo(
            @PathVariable String requestId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            HttpServletRequest request) {
        requireAuthorized(authorization, request);
        if (!VIDEO_REQUEST_ID.equals(requestId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "done");
        result.put("progress", 100);
        result.put("model", "grok-imagine-video-1.5");
        ObjectNode video = result.putObject("video");
        video.put("url", properties.videoUrl());
        video.put("duration", 4);
        video.put("respect_moderation", true);
        result.putObject("usage").put("cost_in_usd_ticks", 1L);
        return json(result);
    }

    private Flux<ServerSentEvent<String>> chatEvents() {
        ObjectNode first = chunk(null);
        first.path("choices").get(0).withObject("/delta")
                .put("content", "loadtest-ok");
        ObjectNode terminal = chunk("stop");
        terminal.set("usage", usage());
        return Flux.just(
                ServerSentEvent.builder(writeJson(first)).build(),
                ServerSentEvent.builder(writeJson(terminal)).build(),
                ServerSentEvent.builder("[DONE]").build());
    }

    private ObjectNode chatCompletion() {
        ObjectNode result = baseChat("chat.completion");
        ObjectNode choice = result.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message")
                .put("role", "assistant")
                .put("content", "loadtest-ok");
        choice.put("finish_reason", "stop");
        result.set("usage", usage());
        return result;
    }

    private ObjectNode chunk(String finishReason) {
        ObjectNode result = baseChat("chat.completion.chunk");
        ObjectNode choice = result.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return result;
    }

    private ObjectNode baseChat(String objectType) {
        return objectMapper.createObjectNode()
                .put("id", "chatcmpl-loadtest")
                .put("object", objectType)
                .put("created", Instant.now().getEpochSecond())
                .put("model", "loadtest-model");
    }

    private ObjectNode usage() {
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("prompt_tokens", 1);
        usage.put("completion_tokens", 1);
        usage.put("total_tokens", 2);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 0);
        usage.putObject("completion_tokens_details").put("reasoning_tokens", 0);
        return usage;
    }

    private ResponseEntity<ObjectNode> json(ObjectNode body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private String writeJson(ObjectNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Loadtest inference response serialization failed.", exception);
        }
    }

    private void requireAuthorized(
            String authorization,
            HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddress)
                && !"::1".equals(remoteAddress)
                && !"0:0:0:0:0:0:0:1".equals(remoteAddress)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        byte[] actual = Objects.toString(authorization, "")
                .getBytes(StandardCharsets.UTF_8);
        byte[] expected = ("Bearer " + inferenceProperties.apiKey())
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
