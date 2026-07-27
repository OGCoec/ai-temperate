package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 Java 21 Windows x64 原生 Reader 能完整解码静态 AVIF，并保留原始容器字节。
 */
final class AvifAiModelIconImageValidationStrategyTest {

    private static final byte[] STATIC_TWO_BY_TWO_AVIF = Base64.getDecoder().decode(
            "AAAAIGZ0eXBhdmlmAAAAAGF2aWZtaWYxbWlhZk1BMUIAAAD5bWV0YQAAAAAAAAAvaGRs"
                    + "cgAAAAAAAAAAcGljdAAAAAAAAAAAAAAAAFBpY3R1cmVIYW5kbGVyAAAAAA5waXRtAAAA"
                    + "AAABAAAAHmlsb2MAAAAARAAAAQABAAAAAQAAASEAAAAbAAAAKGlpbmYAAAAAAAEAAAAa"
                    + "aW5mZQIAAAAAAQAAYXYwMUNvbG9yAAAAAGppcHJwAAAAS2lwY28AAAAUaXNwZQAAAAAA"
                    + "AAACAAAAAgAAABBwaXhpAAAAAAMICAgAAAAMYXYxQ4EADAAAAAATY29scm5jbHgAAgAC"
                    + "AAIAAAAAF2lwbWEAAAAAAAAAAQABBAECgwQAAAAjbWRhdAoFGAA2wCAyEhgAAABQAABA"
                    + "A1Lt5xf080WmIA==");

    @Test
    void fullyDecodesStaticAvif() {
        var result = new AvifAiModelIconImageValidationStrategy()
                .validate(STATIC_TWO_BY_TWO_AVIF, "image/avif");

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.AVIF);
        assertThat(result.width()).isEqualTo(2);
        assertThat(result.height()).isEqualTo(2);
        assertThat(result.frameCount()).isEqualTo(1);
        assertThat(result.storageBytes()).isEqualTo(STATIC_TWO_BY_TWO_AVIF);
    }
}
