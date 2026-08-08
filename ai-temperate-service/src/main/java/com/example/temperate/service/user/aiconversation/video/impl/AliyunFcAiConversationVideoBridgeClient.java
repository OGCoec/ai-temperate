package com.example.temperate.service.user.aiconversation.video.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaType;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * 统一完成主业务服务到 FC 的单次签名调用；探测兼容小型 JSON，视频搬运逐行消费有上限的 NDJSON 进度流。
 */
@Component
final class AliyunFcAiConversationVideoBridgeClient {

    private static final int MAXIMUM_RESPONSE_BYTES = 64 * 1024;
    private static final int MAXIMUM_NDJSON_FRAME_BYTES = 16 * 1024;
    private static final String NDJSON_RESPONSE_MODE = "ndjson-v1";
    private static final MediaType NDJSON_MEDIA_TYPE =
            MediaType.parseMediaType("application/x-ndjson");

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
        String unsignedBody = serialize(new FcRequest(operation, payload, null));
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

    /**
     * 以逐行 NDJSON 方式消费 FC 视频搬运结果。该客户端仍是 MVC 服务中的出站 WebClient，不会把主服务切换为 WebFlux 服务端。
     */
    <T> T invokeTransfer(
            Object payload,
            Class<T> responseType,
            Consumer<AiConversationMediaUploadProgress> progressConsumer) {
        Objects.requireNonNull(progressConsumer);
        String unsignedBody = serialize(new FcRequest(
                "transfer", payload, NDJSON_RESPONSE_MODE));
        String timestamp = Long.toString(clock.instant().getEpochSecond());
        String nonce = cn.hutool.core.lang.id.NanoId.randomNanoId(22);
        String signature = signature(timestamp, nonce, unsignedBody);
        String body = signedBody(timestamp, nonce, signature, unsignedBody);
        JsonNode result = webClient.post()
                .uri(properties.invocationUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(NDJSON_MEDIA_TYPE)
                .header("X-Ait-Timestamp", timestamp)
                .header("X-Ait-Nonce", nonce)
                .header("X-Ait-Signature", signature)
                .bodyValue(body)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.createException().flatMapMany(Flux::error);
                    }
                    MediaType responseContentType = response.headers()
                            .contentType()
                            .orElse(null);
                    if (responseContentType == null
                            || !NDJSON_MEDIA_TYPE.isCompatibleWith(responseContentType)) {
                        return Flux.error(new IllegalStateException(
                                "FC video bridge returned an unexpected content type."));
                    }
                    return splitNdjson(response.bodyToFlux(DataBuffer.class));
                })
                .map(this::parseTransferFrame)
                .handle((frame, sink) -> {
                    if ("progress".equals(frame.type())) {
                        progressConsumer.accept(frame.toProgress(
                                AiConversationMediaUploadState.UPLOADING));
                        return;
                    }
                    if ("verifying".equals(frame.type())) {
                        progressConsumer.accept(frame.toProgress(
                                AiConversationMediaUploadState.VERIFYING));
                        return;
                    }
                    if ("failed".equals(frame.type())) {
                        sink.error(new IllegalStateException(
                                "FC video transfer reported failure."));
                        return;
                    }
                    if ("completed".equals(frame.type())
                            && frame.result() != null && frame.result().isObject()) {
                        sink.next(frame.result());
                        return;
                    }
                    sink.error(new IllegalStateException(
                            "FC video bridge returned an invalid NDJSON frame."));
                })
                .cast(JsonNode.class)
                .next()
                .switchIfEmpty(reactor.core.publisher.Mono.error(
                        new IllegalStateException(
                                "FC video bridge completed without a result.")))
                .block(properties.timeout());
        try {
            return objectMapper.treeToValue(result, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "FC video bridge returned malformed completion JSON.", exception);
        }
    }

    static Flux<String> splitNdjson(Flux<DataBuffer> body) {
        return Flux.defer(() -> {
            StringBuilder pending = new StringBuilder();
            return body.concatMap(buffer -> {
                String chunk;
                try {
                    chunk = buffer.toString(StandardCharsets.UTF_8);
                } finally {
                    DataBufferUtils.release(buffer);
                }
                pending.append(chunk);
                if (pending.length() > MAXIMUM_NDJSON_FRAME_BYTES
                        && pending.indexOf("\n") < 0) {
                    return Flux.error(new IllegalStateException(
                            "FC NDJSON frame exceeds the allowed size."));
                }
                java.util.List<String> lines = new java.util.ArrayList<>();
                int separator;
                while ((separator = pending.indexOf("\n")) >= 0) {
                    String line = pending.substring(0, separator);
                    pending.delete(0, separator + 1);
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    if (line.isEmpty() || line.getBytes(StandardCharsets.UTF_8).length
                            > MAXIMUM_NDJSON_FRAME_BYTES) {
                        return Flux.error(new IllegalStateException(
                                "FC NDJSON frame is invalid."));
                    }
                    lines.add(line);
                }
                return Flux.fromIterable(lines);
            }).concatWith(Flux.defer(() -> {
                if (pending.length() != 0) {
                    return Flux.error(new IllegalStateException(
                            "FC NDJSON stream ended with an incomplete frame."));
                }
                return Flux.empty();
            }));
        });
    }

    private TransferFrame parseTransferFrame(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText();
            long sequence = node.path("sequence").asLong(-1L);
            boolean byteFrame = "progress".equals(type) || "verifying".equals(type);
            long transferredBytes = byteFrame
                    ? node.path("transferredBytes").asLong(-1L) : 0L;
            Long totalBytes = node.hasNonNull("totalBytes")
                    ? node.get("totalBytes").asLong(-1L) : null;
            Integer percent = node.hasNonNull("percent")
                    ? node.get("percent").asInt(-1) : null;
            if (!node.isObject() || node.path("schemaVersion").asInt(-1) != 1
                    || sequence < 1L || transferredBytes < 0L
                    || (totalBytes != null && totalBytes < transferredBytes)
                    || (percent != null && (percent < 0 || percent > 100))) {
                throw new IllegalArgumentException("FC NDJSON frame is invalid.");
            }
            if (byteFrame && totalBytes != null && totalBytes == 0L) {
                throw new IllegalArgumentException("FC NDJSON total bytes are invalid.");
            }
            return new TransferFrame(type, sequence, transferredBytes, totalBytes,
                    percent, node.get("result"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "FC video bridge returned malformed NDJSON.", exception);
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
    private record FcRequest(
            String operation,
            Object payload,
            String responseMode) {
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

    /**
     * 保存单个 NDJSON 帧的已校验字段，避免让 FC 的非可信 JSON 直接影响领域进度模型。
     */
    private record TransferFrame(
            String type,
            long sequence,
            long transferredBytes,
            Long totalBytes,
            Integer percent,
            JsonNode result) {

        private AiConversationMediaUploadProgress toProgress(
                AiConversationMediaUploadState state) {
            Integer resolvedPercent = state == AiConversationMediaUploadState.VERIFYING
                    ? 99 : percent;
            return new AiConversationMediaUploadProgress(
                    AiConversationMediaType.VIDEO,
                    0,
                    1,
                    1,
                    state,
                    transferredBytes,
                    totalBytes,
                    resolvedPercent,
                    sequence,
                    null);
        }
    }
}
