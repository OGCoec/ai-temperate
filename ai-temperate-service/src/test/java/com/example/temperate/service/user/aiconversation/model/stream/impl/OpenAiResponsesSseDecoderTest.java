package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Responses SSE 解帧器可以跨网络字节边界保留事件名、多行 data 与 UTF-8 中文。
 */
final class OpenAiResponsesSseDecoderTest {

    @Test
    void decodesSplitUtf8AndMultipleFramesWithoutLosingEventNames() {
        OpenAiResponsesSseDecoder decoder = new OpenAiResponsesSseDecoder();
        byte[] bytes = ("event: response.output_text.delta\n"
                        + "data: {\"type\":\"response.output_text.delta\",\n"
                        + "data: \"delta\":\"中文\"}\n\n"
                        + ": keepalive\n"
                        + "event: response.completed\n"
                        + "data: {\"type\":\"response.completed\"}\n\n")
                .getBytes(StandardCharsets.UTF_8);
        List<OpenAiResponsesSseEvent> events = new ArrayList<>();

        for (int index = 0; index < bytes.length; index += 3) {
            events.addAll(decoder.accept(
                    bytes,
                    index,
                    Math.min(3, bytes.length - index)));
        }
        events.addAll(decoder.finish());

        assertThat(events).containsExactly(
                new OpenAiResponsesSseEvent(
                        "response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\n\"delta\":\"中文\"}"),
                new OpenAiResponsesSseEvent(
                        "response.completed",
                        "{\"type\":\"response.completed\"}"));
    }
}
