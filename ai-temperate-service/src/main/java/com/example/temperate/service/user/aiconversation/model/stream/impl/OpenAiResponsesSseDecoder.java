package com.example.temperate.service.user.aiconversation.model.stream.impl;

import com.example.temperate.service.user.aiinference.sse.ApiInferenceSseDecoder;
import java.util.List;

/**
 * 按原始字节增量解析 Responses SSE，负责跨网络分片保留 UTF-8、多行 data 和连续事件边界。
 *
 * <p>该解码器不缓存完整响应，只缓存当前行与当前事件；大小上限用于阻止异常上游形成无界内存占用。
 */
final class OpenAiResponsesSseDecoder {

    private final ApiInferenceSseDecoder delegate;

    OpenAiResponsesSseDecoder() {
        this.delegate = new ApiInferenceSseDecoder();
    }

    OpenAiResponsesSseDecoder(
            int maximumLineBytes,
            int maximumEventCharacters) {
        this.delegate = new ApiInferenceSseDecoder(
                maximumLineBytes, maximumEventCharacters);
    }

    List<OpenAiResponsesSseEvent> accept(byte[] bytes, int offset, int length) {
        return delegate.accept(bytes, offset, length).stream()
                .map(event -> new OpenAiResponsesSseEvent(
                        event.eventName(), event.data()))
                .toList();
    }

    List<OpenAiResponsesSseEvent> finish() {
        return delegate.finish().stream()
                .map(event -> new OpenAiResponsesSseEvent(
                        event.eventName(), event.data()))
                .toList();
    }
}
