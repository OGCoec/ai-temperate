package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.springframework.stereotype.Component;

/**
 * 完整解码并验证单帧 PNG 模型图标。
 */
@Component
public final class PngAiModelIconImageValidationStrategy
        extends AbstractImageIoAiModelIconImageValidationStrategy {

    public PngAiModelIconImageValidationStrategy() {
        super(AiModelIconImageFormat.PNG, 1, true, "PNG");
    }

    @Override
    protected void validateContainerBeforeDecode(byte[] bytes) {
        int offset = 8;
        while (offset <= bytes.length - 12) {
            long chunkLength = ((long) (bytes[offset] & 0xff) << 24)
                    | ((long) (bytes[offset + 1] & 0xff) << 16)
                    | ((long) (bytes[offset + 2] & 0xff) << 8)
                    | (bytes[offset + 3] & 0xff);
            if (chunkLength > Integer.MAX_VALUE
                    || offset + 12L + chunkLength > bytes.length) {
                return;
            }
            if (bytes[offset + 4] == 'a'
                    && bytes[offset + 5] == 'c'
                    && bytes[offset + 6] == 'T'
                    && bytes[offset + 7] == 'L') {
                throw unsafeImage();
            }
            offset += 12 + (int) chunkLength;
        }
    }
}
