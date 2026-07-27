package com.example.temperate.service.admin.aimodel.icon.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.AiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.AvifAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.GifAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.IcoAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.JpegAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.PngAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.SvgAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.strategy.impl.WebpAiModelIconImageValidationStrategy;
import com.example.temperate.service.admin.aimodel.icon.image.svg.impl.AiModelIconSvgCssPolicyImpl;
import com.example.temperate.service.admin.aimodel.icon.image.svg.impl.AiModelIconSvgEmbeddedRasterValidatorImpl;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证模型图标门面依据真实内容选择策略，并拒绝声明类型与解码格式不一致的内容。
 */
final class AiModelIconImageValidatorTest {

    private final AiModelIconImageValidator validator = createValidator();

    @Test
    void recognizesDecodedPngInsteadOfTrustingFileName() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);

        AiModelIconImageMetadata metadata =
                validator.validate(output.toByteArray(), "image/png");

        assertThat(metadata.format()).isEqualTo(AiModelIconImageFormat.PNG);
        assertThat(metadata.width()).isEqualTo(2);
        assertThat(metadata.height()).isEqualTo(2);
        assertThat(metadata.frameCount()).isEqualTo(1);
        assertThat(metadata.storageBytes()).isEqualTo(output.toByteArray());
    }

    @Test
    void rejectsContentTypeThatDoesNotMatchDecodedImage() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);

        assertThatThrownBy(() -> validator.validate(output.toByteArray(), "image/png"))
                .isInstanceOf(AiModelIconException.class)
                .extracting(error -> ((AiModelIconException) error).code())
                .isEqualTo(AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID);
    }

    @Test
    void rejectsNonImageBytesWithDedicatedUnsupportedFormatCode() {
        assertThatThrownBy(() -> validator.validate(
                "not-an-image".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "image/png"))
                .isInstanceOf(AiModelIconException.class)
                .extracting(error -> ((AiModelIconException) error).code())
                .isEqualTo(AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_FORMAT_UNSUPPORTED);
    }

    private static AiModelIconImageValidator createValidator() {
        List<AiModelIconImageValidationStrategy> values = List.of(
                new PngAiModelIconImageValidationStrategy(),
                new JpegAiModelIconImageValidationStrategy(),
                new WebpAiModelIconImageValidationStrategy(),
                new GifAiModelIconImageValidationStrategy(),
                new IcoAiModelIconImageValidationStrategy(),
                new AvifAiModelIconImageValidationStrategy(),
                svgStrategy());
        Map<String, AiModelIconImageValidationStrategy> strategies =
                new LinkedHashMap<>();
        for (AiModelIconImageValidationStrategy strategy : values) {
            strategies.put(strategy.type().name(), strategy);
        }
        return new AiModelIconImageValidator(
                new AiModelIconImageFormatDetector(),
                new AiModelIconImageValidationRegistry(strategies));
    }

    private static SvgAiModelIconImageValidationStrategy svgStrategy() {
        return new SvgAiModelIconImageValidationStrategy(
                new AiModelIconSvgCssPolicyImpl(),
                new AiModelIconSvgEmbeddedRasterValidatorImpl(
                        new PngAiModelIconImageValidationStrategy(),
                        new JpegAiModelIconImageValidationStrategy(),
                        new WebpAiModelIconImageValidationStrategy()));
    }
}
