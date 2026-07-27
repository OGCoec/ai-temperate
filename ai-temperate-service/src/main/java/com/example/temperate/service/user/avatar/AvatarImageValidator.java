package com.example.temperate.service.user.avatar;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/**
 * 使用 ImageIO 真实解码头像并验证格式、完整性与像素边界。
 *
 * <p>JPEG、PNG 由 JDK 解码，WebP 由 TwelveMonkeys SPI 解码；扩展名和 Content-Type 不能替代该校验。</p>
 */
@Component
public final class AvatarImageValidator {

    public static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXEL_COUNT = (long) MAX_DIMENSION * MAX_DIMENSION;

    public AvatarImageMetadata validate(byte[] bytes, AvatarImageFormat expectedFormat) {
        if (bytes == null || bytes.length == 0 || expectedFormat == null) {
            throw invalidImage();
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw invalidImage();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalidImage();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                AvatarImageFormat actualFormat = resolveFormat(reader.getFormatName());
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (actualFormat != expectedFormat
                        || width <= 0
                        || height <= 0
                        || width > MAX_DIMENSION
                        || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXEL_COUNT) {
                    throw invalidImage();
                }
                // 读取首帧确保图片数据未损坏；头像业务明确拒绝动画和无法完整解码的伪装文件。
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw invalidImage();
                }
                return new AvatarImageMetadata(actualFormat, width, height);
            } finally {
                reader.dispose();
            }
        } catch (UserAvatarException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new UserAvatarException(
                    UserAvatarErrorCode.INVALID_IMAGE,
                    "上传内容不是可用的 JPEG、PNG 或 WebP 图片。",
                    exception);
        }
    }

    private static AvatarImageFormat resolveFormat(String formatName) {
        String normalized = formatName == null
                ? ""
                : formatName.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "JPEG", "JPG" -> AvatarImageFormat.JPEG;
            case "PNG" -> AvatarImageFormat.PNG;
            case "WEBP" -> AvatarImageFormat.WEBP;
            default -> throw invalidImage();
        };
    }

    private static UserAvatarException invalidImage() {
        return new UserAvatarException(
                UserAvatarErrorCode.INVALID_IMAGE,
                "上传内容不是可用的 JPEG、PNG 或 WebP 图片。");
    }
}
