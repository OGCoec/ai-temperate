package com.example.temperate.service.admin.aimodel.icon.image.svg.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.JpegAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.PngAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.WebpAiModelIconImageValidationStrategy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证 SVG 内嵌图片必须是受限 Data URI，并由既有栅格策略完成真实解码。
 */
final class AiModelIconSvgEmbeddedRasterValidatorImplTest {

    private static final byte[] STATIC_TWO_BY_TWO_WEBP =
            Base64.getDecoder().decode(
                    "UklGRjgAAABXRUJQVlA4ICwAAACQAQCdASoCAAIAAgA0JaACdLoAA5gA"
                            + "/vmTb/+QH/+QH/+QH/8gP+IXeyAwAA==");

    private final AiModelIconSvgEmbeddedRasterValidatorImpl validator =
            new AiModelIconSvgEmbeddedRasterValidatorImpl(
                    new PngAiModelIconImageValidationStrategy(),
                    new JpegAiModelIconImageValidationStrategy(),
                    new WebpAiModelIconImageValidationStrategy());

    @Test
    void acceptsDecodedPngJpegAndWebpDataUris() throws Exception {
        var png = validator.validate(dataUri("png", "image/png"));
        String jpegValue = dataUri("jpeg", "image/jpeg");
        int split = jpegValue.indexOf(',') + 16;
        var jpeg = validator.validate(
                jpegValue.substring(0, split)
                        + "\n"
                        + jpegValue.substring(split));
        var webp = validator.validate(
                "data:image/webp;base64,"
                        + Base64.getEncoder().encodeToString(
                        STATIC_TWO_BY_TWO_WEBP));

        assertThat(png.format()).isEqualTo(AiModelIconImageFormat.PNG);
        assertThat(jpeg.format()).isEqualTo(AiModelIconImageFormat.JPEG);
        assertThat(webp.format()).isEqualTo(AiModelIconImageFormat.WEBP);
        assertThat(png.width()).isEqualTo(2);
        assertThat(jpeg.height()).isEqualTo(2);
        assertThat(webp.width()).isEqualTo(2);
    }

    @Test
    void rejectsMimeSpoofingInvalidBase64AndNestedSvg() throws Exception {
        String jpegPayload = dataUri("jpeg", "image/jpeg")
                .substring("data:image/jpeg;base64,".length());
        for (String value : new String[] {
                "data:image/png;base64," + jpegPayload,
                "data:image/png;base64,not-base64!",
                "data:image/svg+xml;base64,"
                        + Base64.getEncoder().encodeToString(
                                "<svg/>".getBytes(StandardCharsets.UTF_8)),
                "https://example.test/icon.png"
        }) {
            assertUnsafe(() -> validator.validate(value));
        }
    }

    @Test
    void rejectsDecodedPayloadLargerThanOneMib() {
        byte[] oversized = new byte[(1024 * 1024) + 1];
        String value = "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(oversized);

        assertUnsafe(() -> validator.validate(value));
    }

    private static String dataUri(String format, String contentType)
            throws Exception {
        BufferedImage image = new BufferedImage(
                2,
                2,
                "png".equals(format)
                        ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, format, output);
        return "data:" + contentType + ";base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static void assertUnsafe(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }
}
