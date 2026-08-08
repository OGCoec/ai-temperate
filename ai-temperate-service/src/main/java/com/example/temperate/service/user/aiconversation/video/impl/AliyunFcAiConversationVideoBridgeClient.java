package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 统一完成主业务服务到 FC 的单次签名 JSON 调用，禁止 SDK 重试并限制响应体大小。
 */
@Component
final class AliyunFcAiConversationVideoBridgeClient {

    private static final int MAXIMUM_RESPONSE_BYTES = 64 * 1024;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AiConversationVideoGenerationProperties.FunctionCompute properties;
    private final Clock clock;

    AliyunFcAiConversationVideoBridgeClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            AiConversationVideoGenerationProperties properties,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties).functionCompute();
		this.webClient = Objects.requireNonNull(webClientBuilder)
				.clone()
				.codecs(configurer -> configurer.defaultCodecs()
						.maxInMemorySize(MAXIMUM_RESPONSE_BYTES))
				.build();
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    <T> T invoke(String operation, Object payload, Class<T> responseType) {
        String unsignedBody = serialize(new FcRequest(operation, payload));
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = cn.hutool.core.lang.id.NanoId.randomNanoId(22);
        String signature = signature(timestamp, nonce, unsignedBody);
        String body = signedBody(timestamp, nonce, signature, unsignedBody);
        String response = webClient.post()
                .uri(properties.invocationUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Ait-Timestamp", timestamp)
                .header("X-Ait-Nonce", nonce)
                .header("X-Ait-Signature", signature)
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        clientResponse -> clientResponse.createException())
                .bodyToMono(String.class)
                .block(properties.timeout());
        if (response == null
                || response.getBytes(StandardCharsets.UTF_8).length
                        > MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalStateException("FC video bridge response is invalid.");
        }
        try {
            return objectMapper.readValue(response, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "FC video bridge returned malformed JSON.", exception);
        }
    }

    private String signedBody(
            String timestamp,
            String nonce,
            String signature,
            String unsignedBody) {
        try {
            return objectMapper.writeValueAsString(new SignedFcRequest(
                    timestamp,
                    nonce,
                    signature,
                    objectMapper.readTree(unsignedBody)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("FC signed request cannot be serialized.", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("FC video request cannot be serialized.", exception);
        }
    }

    private String signature(String timestamp, String nonce, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.hmacSecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (timestamp + "\n" + nonce + "\n" + body)
                            .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("FC video request signature failed.", exception);
        }
    }

    /**
     * 冻结 FC 操作名与负载，使签名覆盖完整请求语义。
     */
    private record FcRequest(String operation, Object payload) {
    }

    /**
     * 把鉴权字段放入请求体，确保 FC 的流式 Handler 无需依赖 HTTP 触发器对请求头的映射方式。
     */
    private record SignedFcRequest(
            String timestamp,
            String nonce,
            String signature,
            com.fasterxml.jackson.databind.JsonNode request) {
    }
}
