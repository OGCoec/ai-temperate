package com.example.temperate.service.user.aiconversation.image;

import java.util.Locale;

/**
 * 根据生成图片的真实字节签名识别格式，并统一提供持久化所需的标准 MIME 与文件后缀。
 *
 * <p>上游可能忽略请求中的 {@code output_format}，因此生成链路不能信任请求参数、
 * 响应声明或文件名。本类型只识别当前链路明确支持的 PNG、JPEG 和 WebP；未知格式
 * 必须在进入预览、OSS 和数据库之前失败，避免内容、MIME 与 URL 后缀互相矛盾。</p>
 */
public enum AiConversationGeneratedImageFormat {

    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp");

    private static final String GENERATED_FILE_BASENAME = "generated.";

    private final String extension;
    private final String contentType;

    AiConversationGeneratedImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public String generatedFileName() {
        return GENERATED_FILE_BASENAME + extension;
    }

    /**
     * 仅根据文件头识别生成图片格式，不使用上游声称的格式作为回退值。
     *
     * @param bytes Base64 解码后的完整图片字节
     * @return 规范化后的图片格式
     * @throws IllegalStateException 字节为空、文件头不完整或格式不受支持时抛出
     */
    public static AiConversationGeneratedImageFormat detect(byte[] bytes) {
        if (startsWith(bytes, 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (matchesAscii(bytes, 0, "RIFF")
                && matchesAscii(bytes, 8, "WEBP")) {
            return WEBP;
        }
        throw new IllegalStateException(
                "Generated image format is unsupported or truncated");
    }

    /**
     * 把内部图片事件携带的标准 MIME 还原为格式，用于生成与真实字节一致的 OSS 文件名。
     *
     * @param value 内部事件的 MIME，可包含参数
     * @return 对应的生成图片格式
     */
    public static AiConversationGeneratedImageFormat fromContentType(
            String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Generated image content type is required");
        }
        String normalized = value.split(";", 2)[0]
                .trim()
                .toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            return JPEG;
        }
        for (AiConversationGeneratedImageFormat format : values()) {
            if (format.contentType.equals(normalized)) {
                return format;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported generated image content type");
    }

    private static boolean startsWith(byte[] bytes, int... expected) {
        if (bytes == null || bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xFF) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesAscii(
            byte[] bytes,
            int offset,
            String expected) {
        if (bytes == null || offset < 0
                || bytes.length < offset + expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if ((bytes[offset + index] & 0xFF) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
