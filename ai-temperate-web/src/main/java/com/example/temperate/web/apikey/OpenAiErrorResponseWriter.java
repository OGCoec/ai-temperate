package com.example.temperate.web.apikey;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * 该写入器是来让 `/v1` 的 Filter、Controller 和 Worker 对齐 OpenAI 错误结构，同时统一 no-store 并禁止输出内部异常正文。
 */
@Component
public final class OpenAiErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public OpenAiErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public void write(
            HttpServletResponse response,
            int status,
            String message,
            String type,
            String code) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store, private, no-transform");
        response.setHeader("CDN-Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(),
                new Envelope(new Error(message, type, null, code)));
    }

    /** 外层字段名固定为 error，避免普通站点错误格式混入 OpenAI 客户端。 */
    public record Envelope(Error error) {
    }

    /** param 在非字段级错误中固定为空，code 使用稳定机器码。 */
    public record Error(String message, String type, String param, String code) {
    }
}
