package com.example.temperate.service.user.aiinference.sse;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 该解码器是来跨网络分片解析 UTF-8 SSE，兼容 LF/CRLF、多行 data 和注释心跳，并以行与事件上限阻止无界内存占用。
 */
public final class ApiInferenceSseDecoder {

    public static final int DEFAULT_MAXIMUM_LINE_BYTES = 1_048_576;
    public static final int DEFAULT_MAXIMUM_EVENT_CHARACTERS = 2_097_152;

    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private final int maximumLineBytes;
    private final int maximumEventCharacters;
    private String eventName;

    public ApiInferenceSseDecoder() {
        this(DEFAULT_MAXIMUM_LINE_BYTES, DEFAULT_MAXIMUM_EVENT_CHARACTERS);
    }

    public ApiInferenceSseDecoder(
            int maximumLineBytes,
            int maximumEventCharacters) {
        if (maximumLineBytes <= 0 || maximumEventCharacters <= 0) {
            throw new IllegalArgumentException("SSE limits must be positive");
        }
        this.maximumLineBytes = maximumLineBytes;
        this.maximumEventCharacters = maximumEventCharacters;
    }

    public List<ApiInferenceSseEvent> accept(byte[] bytes, int offset, int length) {
        if (bytes == null || offset < 0 || length < 0
                || offset > bytes.length - length) {
            throw new IllegalArgumentException("Invalid SSE byte range");
        }
        List<ApiInferenceSseEvent> events = new ArrayList<>();
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

    public List<ApiInferenceSseEvent> finish() {
        List<ApiInferenceSseEvent> events = new ArrayList<>();
        if (lineBytes.size() > 0) {
            processLine(events);
        }
        dispatch(events);
        return List.copyOf(events);
    }

    private void processLine(List<ApiInferenceSseEvent> events) {
        byte[] current = lineBytes.toByteArray();
        lineBytes.reset();
        int length = current.length;
        if (length > 0 && current[length - 1] == '\r') {
            length--;
        }
        String line = decodeUtf8(current, length);
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
            if ((long) data.length() + value.length() > maximumEventCharacters) {
                throw new IllegalStateException("AI upstream SSE event is too large");
            }
            data.append(value);
        }
    }

    private static String decodeUtf8(byte[] bytes, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("AI upstream SSE contains invalid UTF-8", exception);
        }
    }

    private void dispatch(List<ApiInferenceSseEvent> events) {
        if (data.isEmpty()) {
            eventName = null;
            return;
        }
        String name = eventName == null || eventName.isBlank()
                ? "message" : eventName;
        events.add(new ApiInferenceSseEvent(name, data.toString()));
        eventName = null;
        data.setLength(0);
    }
}
