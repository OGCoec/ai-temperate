package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidator;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.AiModelIconImageValidationStrategy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 为 ImageIO 支持的光栅格式提供全帧解码、尺寸和累计像素限制的共同实现。
 *
 * <p>格式策略只声明稳定格式、可接受的 Reader 名称和最大帧数；读取失败与策略超限使用
 * 不同业务错误，便于调用方区分损坏文件和危险容器。</p>
 */
abstract class AbstractImageIoAiModelIconImageValidationStrategy
        implements AiModelIconImageValidationStrategy {

    private final AiModelIconImageFormat format;
    private final int maximumFrames;
    private final boolean requireSingleFrame;
    private final String[] readerNames;

    AbstractImageIoAiModelIconImageValidationStrategy(
            AiModelIconImageFormat format,
            int maximumFrames,
            boolean requireSingleFrame,
            String... readerNames) {
        this.format = format;
        this.maximumFrames = maximumFrames;
        this.requireSingleFrame = requireSingleFrame;
        this.readerNames = readerNames.clone();
    }

    @Override
    public final AiModelIconImageFormat type() {
        return format;
    }

    @Override
    public final AiModelIconImageMetadata validate(
            byte[] bytes,
            String declaredContentType,
            AiModelIconImageValidationContext context) {
        Objects.requireNonNull(context, "context");
        if (bytes == null || bytes.length == 0) {
            throw invalidImage();
        }
        if (!format.matchesContentType(declaredContentType)) {
            throw invalidImage();
        }
        validateContainerBeforeDecode(bytes);
        ensureDecoderAvailable();
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw invalidImage();
            }
            ImageReader reader = selectReader();
            if (reader == null) {
                throw new AiModelIconException(
                        AiModelIconErrorCode.AI_MODEL_ICON_DECODER_UNAVAILABLE,
                        "Required AI model icon image decoder is unavailable.");
            }
            try {
                reader.setInput(input, false, false);
                int frameCount = reader.getNumImages(true);
                if (frameCount <= 0) {
                    throw invalidImage();
                }
                validateFrameCount(frameCount, maximumFrames, requireSingleFrame);
                long totalPixels = 0;
                int maximumWidth = 0;
                int maximumHeight = 0;
                for (int index = 0; index < frameCount; index++) {
                    int width = reader.getWidth(index);
                    int height = reader.getHeight(index);
                    totalPixels = accumulatePixels(totalPixels, width, height);
                    // 每一帧都执行真实像素解码，避免损坏的中间帧或伪造容器进入 OSS。
                    BufferedImage decoded = reader.read(index);
                    if (decoded == null
                            || decoded.getWidth() != width
                            || decoded.getHeight() != height) {
                        throw invalidImage();
                    }
                    maximumWidth = Math.max(maximumWidth, width);
                    maximumHeight = Math.max(maximumHeight, height);
                }
                return new AiModelIconImageMetadata(
                        format,
                        maximumWidth,
                        maximumHeight,
                        frameCount,
                        bytes);
            } finally {
                reader.dispose();
            }
        } catch (AiModelIconException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AiModelIconException(
                    AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID,
                    "AI model icon image could not be fully decoded.",
                    exception);
        }
    }

    private ImageReader selectReader() {
        for (String readerName : readerNames) {
            Iterator<ImageReader> readers =
                    ImageIO.getImageReadersByFormatName(readerName);
            if (readers.hasNext()) {
                return readers.next();
            }
        }
        return null;
    }

    /**
     * 允许具体格式在 ImageIO 解码前拒绝会被 Reader 忽略的动画容器能力。
     */
    protected void validateContainerBeforeDecode(byte[] bytes) {
        // 默认格式没有额外容器标记；PNG 与 WebP 实现会覆盖此钩子。
    }

    private void ensureDecoderAvailable() {
        for (String readerName : readerNames) {
            if (ImageIO.getImageReadersByFormatName(readerName).hasNext()) {
                return;
            }
        }
        throw new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_DECODER_UNAVAILABLE,
                "Required AI model icon image decoder is unavailable.");
    }

    static void validateFrameCount(
            int frameCount,
            int maximumFrames,
            boolean requireSingleFrame) {
        if (frameCount > maximumFrames
                || (requireSingleFrame && frameCount != 1)) {
            throw unsafeImage();
        }
    }

    static long accumulatePixels(long current, int width, int height) {
        if (width <= 0
                || height <= 0
                || width > AiModelIconImageValidator.MAX_DIMENSION
                || height > AiModelIconImageValidator.MAX_DIMENSION) {
            throw unsafeImage();
        }
        try {
            long updated = Math.addExact(
                    current,
                    Math.multiplyExact((long) width, height));
            if (updated > AiModelIconImageValidator.MAX_TOTAL_PIXELS) {
                throw unsafeImage();
            }
            return updated;
        } catch (ArithmeticException exception) {
            throw unsafeImage();
        }
    }

    static AiModelIconException invalidImage() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID,
                "AI model icon image content is invalid.");
    }

    static AiModelIconException unsafeImage() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                "AI model icon image exceeds its safe processing boundary.");
    }
}
