package com.example.temperate.service.user.aiconversation.image.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewKind;
import com.example.temperate.service.user.aiconversation.image.AiConversationPreparedImagePreview;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证图片预览处理器在 384000 字节边界内保留原图，并只为超限图片生成有界缩略图。
 */
final class AiConversationImagePreviewProcessorImplTest {

    @Test
    void keepsBoundarySizedFinalImageAsFullPreviewWithoutReencoding() throws Exception {
        byte[] bytes = Arrays.copyOf(png(96, 96, false, 7L), 384_000);
        AiConversationGeneratedImage image = finalImage(bytes, "image/png", 96, 96);
        AiConversationImagePreviewProcessorImpl processor = processor(384_000);

        Optional<AiConversationPreparedImagePreview> result =
                processor.prepare(image).join();

        assertThat(result).isPresent();
        AiConversationPreparedImagePreview preview = result.orElseThrow();
        assertThat(preview.bytes()).isEqualTo(bytes);
        assertThat(preview.base64()).hasSize(512_000);
        assertThat(preview.previewKind()).isEqualTo(AiConversationImagePreviewKind.FULL);
        assertThat(preview.requiresUpgrade()).isFalse();
    }

    @Test
    void convertsLargeOpaqueFinalImageIntoBoundedJpegThumbnail() throws Exception {
        byte[] bytes = png(1024, 1024, false, 19L);
        assertThat(bytes.length).isGreaterThan(384_000);
        AiConversationImagePreviewProcessorImpl processor = processor(384_000);

        AiConversationPreparedImagePreview preview = processor.prepare(
                        finalImage(bytes, "image/png", 1024, 1024))
                .join()
                .orElseThrow();

        assertThat(preview.previewKind()).isEqualTo(AiConversationImagePreviewKind.THUMBNAIL);
        assertThat(preview.requiresUpgrade()).isTrue();
        assertThat(preview.contentType()).isEqualTo("image/jpeg");
        assertThat(preview.sizeBytes()).isLessThanOrEqualTo(384_000);
        assertThat(Math.max(preview.width(), preview.height())).isLessThanOrEqualTo(768);
    }

    @Test
    void preservesAlphaByUsingPngForLargeThumbnail() throws Exception {
        byte[] bytes = png(1024, 1024, true, 23L);
        assertThat(bytes.length).isGreaterThan(800_000);
        AiConversationImagePreviewProcessorImpl processor = processor(800_000);

        AiConversationPreparedImagePreview preview = processor.prepare(
                        finalImage(bytes, "image/png", 1024, 1024))
                .join()
                .orElseThrow();

        assertThat(preview.contentType()).isEqualTo("image/png");
        assertThat(preview.previewKind()).isEqualTo(AiConversationImagePreviewKind.THUMBNAIL);
        assertThat(preview.requiresUpgrade()).isTrue();
        assertThat(preview.sizeBytes()).isLessThanOrEqualTo(800_000);
    }

    @Test
    void dropsPreviewWhenFiniteCompressionAttemptsCannotMeetLimit() throws Exception {
        byte[] bytes = png(1024, 1024, true, 29L);
        AiConversationImagePreviewProcessorImpl processor = processor(1_024);

        Optional<AiConversationPreparedImagePreview> result = processor.prepare(
                        finalImage(bytes, "image/png", 1024, 1024))
                .join();

        assertThat(result).isEmpty();
    }

    @Test
    void schedulesLargeCompressionOnTheDedicatedExecutor() throws Exception {
        byte[] bytes = png(1024, 1024, false, 31L);
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AiConversationImagePreviewProcessorImpl processor = processor(
                384_000, queued::set);

        CompletableFuture<Optional<AiConversationPreparedImagePreview>> future =
                processor.prepare(finalImage(bytes, "image/png", 1024, 1024));

        assertThat(future.isDone()).isFalse();
        assertThat(queued.get()).isNotNull();
        queued.get().run();
        assertThat(future.join()).isPresent();
    }

    private static AiConversationImagePreviewProcessorImpl processor(int maximumBytes) {
        return processor(maximumBytes, Runnable::run);
    }

    private static AiConversationImagePreviewProcessorImpl processor(
            int maximumBytes,
            Executor executor) {
        return new AiConversationImagePreviewProcessorImpl(
                new AiConversationImageGenerationProperties(
                        true,
                        "/v1/images/generations",
                        "/v1/images/edits",
                        33_554_432,
                        maximumBytes,
                        768,
                        70,
                        268_435_456L),
                executor);
    }

    private static AiConversationGeneratedImage finalImage(
            byte[] bytes,
            String contentType,
            int width,
            int height) {
        return new AiConversationGeneratedImage(
                "image-0",
                AiConversationGeneratedImagePhase.FINAL,
                (short) 0,
                null,
                contentType,
                width,
                height,
                bytes);
    }

    private static byte[] png(
            int width,
            int height,
            boolean alpha,
            long seed) throws Exception {
        BufferedImage image = new BufferedImage(
                width,
                height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = random.nextInt(0x01000000);
                int opacity = alpha ? random.nextInt(256) : 255;
                image.setRGB(x, y, opacity << 24 | rgb);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
