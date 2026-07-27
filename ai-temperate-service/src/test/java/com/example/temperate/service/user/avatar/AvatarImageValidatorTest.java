package com.example.temperate.service.user.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证头像内容按真实解码结果而不是扩展名或请求 Content-Type 判定。
 */
class AvatarImageValidatorTest {

    private final AvatarImageValidator validator = new AvatarImageValidator();

    @Test
    void acceptsDecodedPngWithinDimensionLimit() throws Exception {
        byte[] bytes = imageBytes("png", 32, 24);

        AvatarImageMetadata metadata = validator.validate(bytes, AvatarImageFormat.PNG);

        assertThat(metadata.width()).isEqualTo(32);
        assertThat(metadata.height()).isEqualTo(24);
        assertThat(metadata.format()).isEqualTo(AvatarImageFormat.PNG);
    }

    @Test
    void acceptsDecodedJpegAnd4096PixelBoundary() throws Exception {
        AvatarImageMetadata jpeg = validator.validate(
                imageBytes("jpeg", 16, 12),
                AvatarImageFormat.JPEG);
        AvatarImageMetadata boundary = validator.validate(
                imageBytes("png", 4096, 1),
                AvatarImageFormat.PNG);

        assertThat(jpeg.format()).isEqualTo(AvatarImageFormat.JPEG);
        assertThat(boundary.width()).isEqualTo(4096);
    }

    @Test
    void rejectsExtensionDisguiseAndBrokenContent() throws Exception {
        assertThatThrownBy(() -> validator.validate(
                        imageBytes("png", 8, 8),
                        AvatarImageFormat.JPEG))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.INVALID_IMAGE);
        assertThatThrownBy(() -> validator.validate(
                        "not-an-image".getBytes(),
                        AvatarImageFormat.WEBP))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.INVALID_IMAGE);
    }

    @Test
    void rejectsDimensionsAbove4096Pixels() throws Exception {
        assertThatThrownBy(() -> validator.validate(
                        imageBytes("png", 4097, 1),
                        AvatarImageFormat.PNG))
                .isInstanceOf(UserAvatarException.class)
                .extracting("code")
                .isEqualTo(UserAvatarErrorCode.INVALID_IMAGE);
    }

    @Test
    void decodesWebpThroughTwelveMonkeysReader() {
        byte[] onePixelWebp = Base64.getDecoder().decode(
                "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/vuUAAA=");

        AvatarImageMetadata metadata =
                validator.validate(onePixelWebp, AvatarImageFormat.WEBP);

        assertThat(metadata.format()).isEqualTo(AvatarImageFormat.WEBP);
        assertThat(metadata.width()).isEqualTo(1);
        assertThat(metadata.height()).isEqualTo(1);
    }

    private static byte[] imageBytes(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return output.toByteArray();
    }
}
