package com.example.temperate.service.admin.aimodel.icon.image.svg.impl;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.AiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.JpegAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.PngAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.WebpAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.svg.AiModelIconSvgEmbeddedRasterMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.svg.AiModelIconSvgEmbeddedRasterValidator;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 解码并复用现有 PNG、JPEG、WebP 策略验证可信官方 SVG 的内嵌栅格图片。
 *
 * <p>data URI 必须是精确 MIME、Base64 编码且单项不超过一 MiB；原始文件名、SVG 声明尺寸
 * 或 data URI 文本都不能替代真实图片解码结果。</p>
 */
@Component
public final class AiModelIconSvgEmbeddedRasterValidatorImpl
        implements AiModelIconSvgEmbeddedRasterValidator {

    private static final int MAX_DECODED_BYTES = 1024 * 1024;
    private static final Pattern DATA_URI = Pattern.compile(
            "^data:(image/(?:png|jpeg|webp));base64,"
                    + "([A-Za-z0-9+/\\r\\n\\t ]*={0,2})$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ASCII_WHITESPACE =
            Pattern.compile("[\\r\\n\\t ]");

    private final Map<String, AiModelIconImageValidationStrategy> strategies;

    public AiModelIconSvgEmbeddedRasterValidatorImpl(
            PngAiModelIconImageValidationStrategy pngStrategy,
            JpegAiModelIconImageValidationStrategy jpegStrategy,
            WebpAiModelIconImageValidationStrategy webpStrategy) {
        this.strategies = Map.of(
                "image/png", Objects.requireNonNull(pngStrategy),
                "image/jpeg", Objects.requireNonNull(jpegStrategy),
                "image/webp", Objects.requireNonNull(webpStrategy));
    }

    @Override
    public AiModelIconSvgEmbeddedRasterMetadata validate(String dataUri) {
        if (dataUri == null || dataUri.length() > 2 * MAX_DECODED_BYTES) {
            throw unsafeEmbeddedImage();
        }
        Matcher matcher = DATA_URI.matcher(dataUri);
        if (!matcher.matches()) {
            throw unsafeEmbeddedImage();
        }
        String contentType = matcher.group(1).toLowerCase(Locale.ROOT);
        String encoded = ASCII_WHITESPACE.matcher(matcher.group(2)).replaceAll("");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw unsafeEmbeddedImage();
        }
        if (decoded.length == 0 || decoded.length > MAX_DECODED_BYTES) {
            throw unsafeEmbeddedImage();
        }
        AiModelIconImageMetadata metadata;
        try {
            metadata = strategies.get(contentType).validate(
                    decoded,
                    contentType,
                    AiModelIconImageValidationContext.strict());
        } catch (AiModelIconException exception) {
            if (exception.code()
                    == AiModelIconErrorCode.AI_MODEL_ICON_DECODER_UNAVAILABLE) {
                throw exception;
            }
            // 内嵌图片属于 SVG 主动内容边界；MIME 伪造或损坏载荷必须统一按不安全 SVG 拒绝。
            throw unsafeEmbeddedImage(exception);
        }
        if (metadata.frameCount() != 1
                || (metadata.format() != AiModelIconImageFormat.PNG
                && metadata.format() != AiModelIconImageFormat.JPEG
                && metadata.format() != AiModelIconImageFormat.WEBP)) {
            throw unsafeEmbeddedImage();
        }
        return new AiModelIconSvgEmbeddedRasterMetadata(
                metadata.format(),
                metadata.width(),
                metadata.height(),
                decoded.length);
    }

    private static AiModelIconException unsafeEmbeddedImage() {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                "AI model icon SVG embedded image is outside the safe policy.");
    }

    private static AiModelIconException unsafeEmbeddedImage(Throwable cause) {
        return new AiModelIconException(
                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE,
                "AI model icon SVG embedded image is outside the safe policy.",
                cause);
    }
}
