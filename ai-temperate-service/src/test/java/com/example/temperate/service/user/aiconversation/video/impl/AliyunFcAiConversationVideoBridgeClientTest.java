package com.example.temperate.service.user.aiconversation.video.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;

/**
 * 验证 FC 进度流即使在网络分块把一条 NDJSON 拆开时，主服务仍按完整行消费，而不会把半个 JSON 误当成事件。
 */
final class AliyunFcAiConversationVideoBridgeClientTest {

    @Test
    void joinsNetworkChunksBeforeEmittingNdjsonFrame() {
        DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

        List<String> frames = AliyunFcAiConversationVideoBridgeClient.splitNdjson(
                        Flux.just(
                                buffers.wrap("{\"type\":\"pro".getBytes(StandardCharsets.UTF_8)),
                                buffers.wrap("gress\",\"sequence\":1}\n{\"type\":\"completed\"".getBytes(StandardCharsets.UTF_8)),
                                buffers.wrap(",\"sequence\":2}\n".getBytes(StandardCharsets.UTF_8))))
                .collectList()
                .block();

        assertEquals(List.of(
                "{\"type\":\"progress\",\"sequence\":1}",
                "{\"type\":\"completed\",\"sequence\":2}"), frames);
    }
}
