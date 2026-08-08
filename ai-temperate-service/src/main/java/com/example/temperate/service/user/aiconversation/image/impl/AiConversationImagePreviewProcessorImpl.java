package com.example.temperate.service.user.aiconversation.image.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImage;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewKind;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewProcessor;
import com.example.temperate.service.user.aiconversation.image.AiConversationPreparedImagePreview;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 按原始字节阈值选择完整预览或有限次数缩略图压缩，并把重编码移出模型 Reactor 回调线程。
 */
@Service
public final class AiConversationImagePreviewProcessorImpl
        implements AiConversationImagePreviewProcessor {

    private final int maximumInlinePreviewBytes;
    private final int thumbnailMaxEdgePixels;
    private final int thumbnailJpegQuality;
    private final Executor executor;

    public AiConversationImagePreviewProcessorImpl(
            AiConversationImageGenerationProperties properties,
            @Qualifier("aiConversationImagePreviewExecutor") Executor executor) {
        Objects.requireNonNull(properties);
        this.maximumInlinePreviewBytes = properties.maximumInlinePreviewBytes();
        this.thumbnailMaxEdgePixels = properties.thumbnailMaxEdgePixels();
        this.thumbnailJpegQuality = properties.thumbnailJpegQuality();
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public CompletableFuture<Optional<AiConversationPreparedImagePreview>> prepare(
            AiConversationGeneratedImage image) {
        Objects.requireNonNull(image);
        if (image.sizeBytes() <= maximumInlinePreviewBytes) {
            return CompletableFuture.completedFuture(Optional.of(
                    directPreview(image)));
        }
        try {
            // 只有超出 SSE 上限的图片才进入有界执行器，避免在 Reactor 上游回调中解码和缩放大图。
            return CompletableFuture.supplyAsync(
                            () -> compressedPreview(image), executor)
                    .exceptionally(ignored -> Optional.empty());
        } catch (RuntimeException rejected) {
            // 队列饱和时放弃易失预览，原始图片仍沿正式 OSS 链路完成持久化。
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private AiConversationPreparedImagePreview directPreview(
            AiConversationGeneratedImage image) {
        boolean full = image.phase() == AiConversationGeneratedImagePhase.FINAL;
        return new AiConversationPreparedImagePreview(
                image.imageId(),
                image.phase(),
                image.outputIndex(),
                image.partialImageIndex(),
                image.contentType(),
                image.width(),
                image.height(),
                full
                        ? AiConversationImagePreviewKind.FULL
                        : AiConversationImagePreviewKind.THUMBNAIL,
                !full,
                image.bytes());
    }

    private Optional<AiConversationPreparedImagePreview> compressedPreview(
            AiConversationGeneratedImage image) {
        try {
            BufferedImage source;
            try (InputStream input = image.openStream()) {
                source = ImageIO.read(input);
            }
            if (source == null) {
                return Optional.empty();
            }
            boolean preserveAlpha = source.getColorModel().hasAlpha();
            for (CompressionAttempt attempt : attempts()) {
                BufferedImage scaled = scale(source, attempt.maxEdge(), preserveAlpha);
                byte[] encoded = preserveAlpha
                        ? encodePng(scaled)
                        : encodeJpeg(scaled, attempt.jpegQuality());
                if (encoded.length <= maximumInlinePreviewBytes) {
                    return Optional.of(new AiConversationPreparedImagePreview(
                            image.imageId(),
                            image.phase(),
                            image.outputIndex(),
                            image.partialImageIndex(),
                            preserveAlpha ? "image/png" : "image/jpeg",
                            scaled.getWidth(),
                            scaled.getHeight(),
                            AiConversationImagePreviewKind.THUMBNAIL,
                            true,
                            encoded));
                }
            }
            return Optional.empty();
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private List<CompressionAttempt> attempts() {
        int[] edges = {
                thumbnailMaxEdgePixels,
                Math.min(thumbnailMaxEdgePixels, 640),
                Math.min(thumbnailMaxEdgePixels, 512),
                Math.min(thumbnailMaxEdgePixels, 384)
        };
        int[] qualities = {
                thumbnailJpegQuality,
                Math.min(thumbnailJpegQuality, 65),
                Math.min(thumbnailJpegQuality, 60),
                Math.min(thumbnailJpegQuality, 55)
        };
        List<CompressionAttempt> values = new ArrayList<>(4);
        for (int index = 0; index < edges.length; index++) {
            CompressionAttempt next = new CompressionAttempt(edges[index], qualities[index]);
            if (values.isEmpty() || !values.get(values.size() - 1).equals(next)) {
                values.add(next);
            }
        }
        return List.copyOf(values);
    }

    private static BufferedImage scale(
            BufferedImage source,
            int maximumEdge,
            boolean preserveAlpha) {
        double ratio = Math.min(
                1D,
                (double) maximumEdge
                        / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(
                width,
                height,
                preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG writer is unavailable.");
        }
        return output.toByteArray();
    }

    private static byte[] encodeJpeg(
            BufferedImage image,
            int qualityPercent) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG writer is unavailable.");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam parameter = writer.getDefaultWriteParam();
            parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameter.setCompressionQuality(qualityPercent / 100F);
            writer.write(null, new IIOImage(image, null, null), parameter);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private record CompressionAttempt(int maxEdge, int jpegQuality) {
    }
}
