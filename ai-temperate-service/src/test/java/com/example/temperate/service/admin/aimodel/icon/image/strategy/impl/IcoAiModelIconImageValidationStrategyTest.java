package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证 ICO 保留原容器并解码全部图标条目，超过二十个条目时拒绝。
 */
final class IcoAiModelIconImageValidationStrategyTest {

    private final IcoAiModelIconImageValidationStrategy strategy =
            new IcoAiModelIconImageValidationStrategy();

    @Test
    void preservesAndDecodesTwentyIcoEntries() throws Exception {
        byte[] bytes = icoWithEmbeddedPngEntries(
                IcoAiModelIconImageValidationStrategy.MAX_ENTRIES);

        var result = strategy.validate(bytes, "image/vnd.microsoft.icon");

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.ICO);
        assertThat(result.frameCount())
                .isEqualTo(IcoAiModelIconImageValidationStrategy.MAX_ENTRIES);
        assertThat(result.storageBytes()).isEqualTo(bytes);
    }

    @Test
    void rejectsTwentyOneIcoEntries() throws Exception {
        byte[] bytes = icoWithEmbeddedPngEntries(
                IcoAiModelIconImageValidationStrategy.MAX_ENTRIES + 1);

        assertThatThrownBy(() -> strategy.validate(bytes, "image/x-icon"))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }

    private static byte[] icoWithEmbeddedPngEntries(int entries) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff10a37f);
        ByteArrayOutputStream pngOutput = new ByteArrayOutputStream();
        ImageIO.write(image, "png", pngOutput);
        byte[] png = pngOutput.toByteArray();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        writeLittleEndianShort(data, 0);
        writeLittleEndianShort(data, 1);
        writeLittleEndianShort(data, entries);
        int imageOffset = 6 + (16 * entries);
        for (int index = 0; index < entries; index++) {
            data.writeByte(1);
            data.writeByte(1);
            data.writeByte(0);
            data.writeByte(0);
            writeLittleEndianShort(data, 1);
            writeLittleEndianShort(data, 32);
            writeLittleEndianInt(data, png.length);
            writeLittleEndianInt(data, imageOffset + (png.length * index));
        }
        for (int index = 0; index < entries; index++) {
            data.write(png);
        }
        data.flush();
        return output.toByteArray();
    }

    private static void writeLittleEndianShort(
            DataOutputStream output,
            int value) throws Exception {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
    }

    private static void writeLittleEndianInt(
            DataOutputStream output,
            int value) throws Exception {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
        output.writeByte((value >>> 16) & 0xff);
        output.writeByte((value >>> 24) & 0xff);
    }
}
