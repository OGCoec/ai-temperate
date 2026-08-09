package com.example.temperate.functions.video;

import com.example.temperate.functions.video.dto.VideoTransferResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 将 FC 内部的视频 OSS 搬运状态逐行写为 NDJSON，确保每个进度帧都可在传输尚未结束时被主服务解析。
 */
final class VideoTransferNdjsonWriter {

    private final OutputStream output;
    private final ObjectMapper objectMapper;

    VideoTransferNdjsonWriter(OutputStream output, ObjectMapper objectMapper) {
        this.output = Objects.requireNonNull(output);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    void progress(long sequence, long transferredBytes, Long totalBytes) {
        ObjectNode frame = frame("progress", sequence);
        frame.put("transferredBytes", Math.max(0L, transferredBytes));
        if (totalBytes != null && totalBytes > 0L) {
            long safeTransferred = Math.min(totalBytes, Math.max(0L, transferredBytes));
            frame.put("totalBytes", totalBytes);
            // 所有字节写完后仍需等待 OSS complete 与 HEAD，浏览器在此之前不得显示 100%。
            frame.put("percent", Math.min(99, (int) ((safeTransferred * 100L) / totalBytes)));
        }
        write(frame);
    }

    void verifying(long sequence, long transferredBytes, Long totalBytes) {
        ObjectNode frame = frame("verifying", sequence);
        frame.put("transferredBytes", Math.max(0L, transferredBytes));
        if (totalBytes != null && totalBytes > 0L) {
            frame.put("totalBytes", totalBytes);
        }
        // 即使源端没有 Content-Length，所有字节交给 OSS 后也必须进入 99% 的校验阶段。
        frame.put("percent", 99);
        write(frame);
    }

    void completed(long sequence, VideoTransferResponse response) {
        ObjectNode frame = frame("completed", sequence);
        frame.put("percent", 100);
        frame.set("result", objectMapper.valueToTree(Objects.requireNonNull(response)));
        write(frame);
    }

    void failed(long sequence, String errorCode) {
        ObjectNode frame = frame("failed", sequence);
        // 失败帧是 FC 唯一跨网络的异常边界，始终通过固定枚举白名单过滤调用方传入值。
        frame.put("errorCode", VideoTransferFailureCode.safeCode(errorCode));
        write(frame);
    }

    private ObjectNode frame(String type, long sequence) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("schemaVersion", 1);
        frame.put("type", type);
        frame.put("sequence", sequence);
        return frame;
    }

    private void write(ObjectNode frame) {
        try {
            output.write(objectMapper.writeValueAsBytes(frame));
            output.write('\n');
            output.flush();
        } catch (IOException exception) {
            throw new UncheckedIOException("FC video progress stream cannot be written.", exception);
        }
    }
}
