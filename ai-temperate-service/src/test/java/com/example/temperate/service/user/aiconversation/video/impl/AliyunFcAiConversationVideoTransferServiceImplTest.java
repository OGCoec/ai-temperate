package com.example.temperate.service.user.aiconversation.video.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationErrorCode;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.generation.progress.AiConversationMediaUploadProgress;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoTransferCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 验证主服务在 FC 搬运失败时只向浏览器传播安全阶段码，同时维持原有统一业务失败语义。
 */
final class AliyunFcAiConversationVideoTransferServiceImplTest {

    @Test
    void publishesFcStageFailureCodeToMediaProgress() {
        AiConversationVideoGenerationProperties videoProperties = videoProperties();
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(ignored -> Mono.just(ClientResponse
                        .create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE,
                                "application/x-ndjson")
                        .body("""
                                {"schemaVersion":1,"type":"failed","sequence":1,"errorCode":"SOURCE_OPEN_FAILED"}
                                """)
                        .build()));
        AliyunFcAiConversationVideoBridgeClient client =
                new AliyunFcAiConversationVideoBridgeClient(
                        builder,
                        new ObjectMapper(),
                        videoProperties,
                        Clock.systemUTC());
        AliyunFcAiConversationVideoTransferServiceImpl service =
                new AliyunFcAiConversationVideoTransferServiceImpl(
                        client,
                        videoProperties,
                        attachmentProperties());
        List<AiConversationMediaUploadProgress> progressEvents = new ArrayList<>();

        AiConversationException failure = assertThrows(
                AiConversationException.class,
                () -> service.transfer(command(), progressEvents::add));

        assertEquals(AiConversationErrorCode.AI_VIDEO_OSS_TRANSFER_FAILED,
                failure.code());
        assertEquals(1, progressEvents.size());
        assertEquals("SOURCE_OPEN_FAILED", progressEvents.getFirst().errorCode());
    }

    private static AiConversationVideoTransferCommand command() {
        return new AiConversationVideoTransferCommand(
                "A".repeat(38),
                "https://vidgen.x.ai/video/result.mp4",
                "ai/video/test-result.mp4",
                "video/mp4",
                2_147_483_648L);
    }

    private static AiConversationVideoGenerationProperties videoProperties() {
        AiConversationVideoGenerationProperties defaults =
                AiConversationVideoGenerationProperties.officialDefaults();
        return new AiConversationVideoGenerationProperties(
                false,
                defaults.pollInterval(),
                defaults.maximumPollingDuration(),
                defaults.maximumResponseJsonBytes(),
                defaults.endpoints(),
                defaults.version15(),
                defaults.legacy(),
                new AiConversationVideoGenerationProperties.FunctionCompute(
                        "https://fc.example.test/invoke",
                        "test-hmac-secret-with-at-least-32-bytes",
                        Duration.ofSeconds(5),
                        "ai/video/",
                        2_147_483_648L,
                        List.of("vidgen.x.ai", "api.x.ai")));
    }

    private static AiConversationAttachmentProperties attachmentProperties() {
        return new AiConversationAttachmentProperties(
                "test-bucket",
                "us-west-1",
                "https://oss-us-west-1.aliyuncs.com",
                "https://media.example.test",
                Duration.ofMinutes(10),
                Duration.ofMinutes(10),
                100 * 1024 * 1024L,
                8,
                200 * 1024 * 1024L,
                3,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                2,
                1024,
                0,
                2048,
                0,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                2);
    }
}
