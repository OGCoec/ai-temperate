package com.example.temperate.service.admin.aimodel.icon.image;

import jakarta.annotation.PostConstruct;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * 在应用启动阶段确认当前运行环境具备计划固定要求的 AVIF 完整解码器。
 *
 * <p>当前部署目标固定为 Java 21 Windows x64；若原生 ImageIO Reader 未能加载，应用必须
 * 明确启动失败，禁止把 AVIF 静默降级为只检查容器魔数。</p>
 */
@Component
public final class AiModelIconRequiredDecoderVerifier {

    private static final byte[] AVIF_STARTUP_PROBE = Base64.getDecoder().decode(
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAAD5bWV0YQAAAAAAAAAvaGRs"
                    + "cgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAFBpY3R1cmVIYW5kbGVyAAAAAA5waXRtAAAA"
                    + "AAABAAAAHmlsb2MAAAAARAAAAQABAAAAAQAAASEAAAAbAAAAKGlpbmYAAAAAAAEAAAAa"
                    + "aW5mZQIAAAAAAQAAYXYwMUNvbG9yAAAAAGppcHJwAAAAS2lwY28AAAAUaXNwZQAAAAAA"
                    + "AAACAAAAAgAAABBwaXhpAAAAAAMICAgAAAAMYXYxQ4EADAAAAAATY29scm5jbHgAAgAC"
                    + "AAIAAAAAF2lwbWEAAAAAAAAAAQABBAECgwQAAAAjbWRhdAoFGAA2wCAyEhgAAABQAABA"
                    + "A1Lt5xf080WmIA==");

    @PostConstruct
    public void verifyRequiredDecoders() {
        verifyAvifReaderAvailable(
                ImageIO.getImageReadersByFormatName("AVIF").hasNext());
        try {
            // 仅发现 SPI 不足以证明 JNI 可用；启动探针必须完成一次真实像素解码。
            BufferedImage decoded = ImageIO.read(
                    new ByteArrayInputStream(AVIF_STARTUP_PROBE));
            if (decoded == null
                    || decoded.getWidth() != 2
                    || decoded.getHeight() != 2) {
                throw new IllegalStateException(
                        "Required AVIF ImageIO reader failed its startup probe.");
            }
        } catch (IOException | RuntimeException | LinkageError exception) {
            throw new IllegalStateException(
                    "Required AVIF ImageIO reader failed its startup probe.",
                    exception);
        }
    }

    static void verifyAvifReaderAvailable(boolean available) {
        if (!available) {
            throw new IllegalStateException(
                    "Required AVIF ImageIO reader is unavailable.");
        }
    }
}
