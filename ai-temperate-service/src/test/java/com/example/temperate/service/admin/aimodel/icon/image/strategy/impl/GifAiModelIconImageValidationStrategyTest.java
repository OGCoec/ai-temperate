package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;

/**
 * 验证 GIF 会保留原动画字节、解码全部帧，并在一百二十帧边界后拒绝。
 */
final class GifAiModelIconImageValidationStrategyTest {

    private final GifAiModelIconImageValidationStrategy strategy =
            new GifAiModelIconImageValidationStrategy();

    @Test
    void preservesAndDecodesEveryGifFrameAtBoundary() throws Exception {
        byte[] bytes = animatedGif(GifAiModelIconImageValidationStrategy.MAX_FRAMES);

        var result = strategy.validate(bytes, "image/gif");

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.GIF);
        assertThat(result.frameCount())
                .isEqualTo(GifAiModelIconImageValidationStrategy.MAX_FRAMES);
        assertThat(result.storageBytes()).isEqualTo(bytes);
    }

    @Test
    void rejectsGifWithOneHundredTwentyOneFrames() throws Exception {
        byte[] bytes = animatedGif(
                GifAiModelIconImageValidationStrategy.MAX_FRAMES + 1);

        assertThatThrownBy(() -> strategy.validate(bytes, "image/gif"))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }

    @Test
    void rejectsDamagedGifContainer() throws Exception {
        byte[] valid = animatedGif(2);
        byte[] truncated = java.util.Arrays.copyOf(valid, 12);

        assertThatThrownBy(() -> strategy.validate(truncated, "image/gif"))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_INVALID));
    }

    private static byte[] animatedGif(int frames) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            writer.prepareWriteSequence(null);
            for (int index = 0; index < frames; index++) {
                BufferedImage image =
                        new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(0, 0, 0xff000000 | index);
                writer.writeToSequence(
                        new IIOImage(image, null, null),
                        writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}
