package com.example.temperate.service.admin.aimodel.icon.image;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 根据文件签名识别模型图标真实格式，并把后续完整验证交给对应格式策略。
 *
 * <p>检测器不读取文件名、URL 后缀或请求 Content-Type，避免伪造扩展名绕过解码和 SVG
 * 安全检查。SVG 没有固定二进制魔数，因此这里只识别候选 XML 文档，根元素和内容仍由
 * SVG 策略严格验证。</p>
 */
@Component
public final class AiModelIconImageFormatDetector {

    private static final int SVG_SNIFF_BYTES = 1024;

    public AiModelIconImageFormat detect(byte[] bytes) {
        if (startsWith(bytes, new int[] {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return AiModelIconImageFormat.PNG;
        }
        if (startsWith(bytes, new int[] {0xff, 0xd8, 0xff})) {
            return AiModelIconImageFormat.JPEG;
        }
        if (startsWithAscii(bytes, "GIF87a") || startsWithAscii(bytes, "GIF89a")) {
            return AiModelIconImageFormat.GIF;
        }
        if (bytes != null
                && bytes.length >= 12
                && startsWithAscii(bytes, "RIFF")
                && asciiEquals(bytes, 8, "WEBP")) {
            return AiModelIconImageFormat.WEBP;
        }
        if (startsWith(bytes, new int[] {0x00, 0x00, 0x01, 0x00})) {
            return AiModelIconImageFormat.ICO;
        }
        if (isAvif(bytes)) {
            return AiModelIconImageFormat.AVIF;
        }
        if (isSvgCandidate(bytes)) {
            return AiModelIconImageFormat.SVG;
        }
        throw new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED,
                "AI model icon image format is unsupported.");
    }

    private static boolean isAvif(byte[] bytes) {
        if (bytes == null || bytes.length < 16 || !asciiEquals(bytes, 4, "ftyp")) {
            return false;
        }
        int limit = Math.min(bytes.length - 3, 64);
        for (int offset = 8; offset < limit; offset += 4) {
            if (asciiEquals(bytes, offset, "avif") || asciiEquals(bytes, offset, "avis")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSvgCandidate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        String prefix = new String(
                bytes,
                0,
                Math.min(bytes.length, SVG_SNIFF_BYTES),
                StandardCharsets.UTF_8);
        String normalized = prefix
                .replace("\uFEFF", "")
                .stripLeading()
                .toLowerCase(Locale.ROOT);
        return normalized.startsWith("<") && normalized.contains("<svg");
    }

    private static boolean startsWithAscii(byte[] bytes, String expected) {
        return asciiEquals(bytes, 0, expected);
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        if (bytes == null || offset < 0 || bytes.length - offset < expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if ((bytes[offset + index] & 0xff) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, int[] expected) {
        if (bytes == null || bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
