package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.springframework.stereotype.Component;

/**
 * 使用 TwelveMonkeys ImageIO Reader 完整解码并验证单帧 WebP 模型图标。
 */
@Component
public final class WebpAiModelIconImageValidationStrategy
        extends AbstractImageIoAiModelIconImageValidationStrategy {

    public WebpAiModelIconImageValidationStrategy() {
        super(AiModelIconImageFormat.WEBP, 1, true, "WEBP");
    }

    @Override
    protected void validateContainerBeforeDecode(byte[] bytes) {
        boolean extendedAnimationFlag = bytes.length > 20
                && asciiEquals(bytes, 12, "VP8X")
                && (bytes[20] & 0x02) != 0;
        if (extendedAnimationFlag || containsChunk(bytes, "ANIM")) {
            throw unsafeImage();
        }
    }

    private static boolean containsChunk(byte[] bytes, String value) {
        int offset = 12;
        while (offset <= bytes.length - 8) {
            if (asciiEquals(bytes, offset, value)) {
                return true;
            }
            long chunkLength = (bytes[offset + 4] & 0xffL)
                    | ((bytes[offset + 5] & 0xffL) << 8)
                    | ((bytes[offset + 6] & 0xffL) << 16)
                    | ((bytes[offset + 7] & 0xffL) << 24);
            long next = offset + 8L + chunkLength + (chunkLength & 1L);
            if (next > bytes.length || next > Integer.MAX_VALUE) {
                return false;
            }
            offset = (int) next;
        }
        return false;
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String value) {
        if (offset < 0 || bytes.length - offset < value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if ((bytes[offset + index] & 0xff) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
