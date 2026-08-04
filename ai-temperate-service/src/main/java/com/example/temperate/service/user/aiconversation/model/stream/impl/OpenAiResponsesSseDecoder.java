package com.example.temperate.service.user.aiconversation.model.stream.impl;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 按原始字节增量解析 Responses SSE，负责跨网络分片保留 UTF-8、多行 data 和连续事件边界。
 *
 * <p>该解码器不缓存完整响应，只缓存当前行与当前事件；大小上限用于阻止异常上游形成无界内存占用。
 */
final class OpenAiResponsesSseDecoder {

    private static final int DEFAULT_MAXIMUM_LINE_BYTES = 1_048_576;
    private static final int DEFAULT_MAXIMUM_EVENT_CHARACTERS = 2_097_152;

    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private final int maximumLineBytes;
    private final int maximumEventCharacters;
    private String eventName;

    OpenAiResponsesSseDecoder() {
        this(DEFAULT_MAXIMUM_LINE_BYTES, DEFAULT_MAXIMUM_EVENT_CHARACTERS);
    }

    OpenAiResponsesSseDecoder(
            int maximumLineBytes,
            int maximumEventCharacters) {
        if (maximumLineBytes <= 0 || maximumEventCharacters <= 0) {
            throw new IllegalArgumentException("SSE limits must be positive");
        }
        this.maximumLineBytes = maximumLineBytes;
        this.maximumEventCharacters = maximumEventCharacters;
    }

    List<OpenAiResponsesSseEvent> accept(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length < 0
                || offset + length > (bytes == null ? 0 : bytes.length)) {
            throw new IllegalArgumentException("Invalid SSE byte range");
        }
        List<OpenAiResponsesSseEvent> events = new ArrayList<>();
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            byte value = bytes[index];
            if (value == '\n') {
                processLine(events);
                continue;
            }
            if (lineBytes.size() >= maximumLineBytes) {
                throw new IllegalStateException("AI upstream SSE line is too large");
            }
            lineBytes.write(value);
        }
        return List.copyOf(events);
    }

    List<OpenAiResponsesSseEvent> finish() {
        List<OpenAiResponsesSseEvent> events = new ArrayList<>();
        if (lineBytes.size() > 0) {
            processLine(events);
        }
        dispatch(events);
        return List.copyOf(events);
    }

    private void processLine(List<OpenAiResponsesSseEvent> events) {
        byte[] current = lineBytes.toByteArray();
        lineBytes.reset();
        int length = current.length;
        if (length > 0 && current[length - 1] == '\r') {
            length--;
        }
        String line = new String(current, 0, length, StandardCharsets.UTF_8);
        if (line.isEmpty()) {
            dispatch(events);
            return;
        }
        if (line.charAt(0) == ':') {
            return;
        }
        int separator = line.indexOf(':');
        String field = separator < 0 ? line : line.substring(0, separator);
        String value = separator < 0 ? "" : line.substring(separator + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        if ("event".equals(field)) {
            eventName = value;
        } else if ("data".equals(field)) {
            if (!data.isEmpty()) {
                data.append('\n');
            }
            if ((long) data.length() + value.length()
                    > maximumEventCharacters) {
                throw new IllegalStateException("AI upstream SSE event is too large");
            }
            data.append(value);
        }
    }

    private void dispatch(List<OpenAiResponsesSseEvent> events) {
        if (data.isEmpty()) {
            eventName = null;
            return;
        }
        String name = eventName == null || eventName.isBlank()
                ? "message"
                : eventName;
        events.add(new OpenAiResponsesSseEvent(name, data.toString()));
        eventName = null;
        data.setLength(0);
    }
}
