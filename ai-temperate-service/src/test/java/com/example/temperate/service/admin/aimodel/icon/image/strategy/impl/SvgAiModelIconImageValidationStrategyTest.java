package com.example.temperate.service.admin.aimodel.icon.image.strategy.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidationContext;
import com.example.temperate.service.admin.aimodel.icon.image.svg.impl.AiModelIconSvgCssPolicyImpl;
import com.example.temperate.service.admin.aimodel.icon.image.svg.impl.AiModelIconSvgEmbeddedRasterValidatorImpl;
import com.example.temperate.service.admin.aimodel.icon.remote.config.AiModelIconVendor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 验证 SVG 严格档位和可信官方兼容档位都保持静态、闭合且有界的资源安全边界。
 */
final class SvgAiModelIconImageValidationStrategyTest {

    private final SvgAiModelIconImageValidationStrategy strategy =
            new SvgAiModelIconImageValidationStrategy(
                    new AiModelIconSvgCssPolicyImpl(),
                    new AiModelIconSvgEmbeddedRasterValidatorImpl(
                            new PngAiModelIconImageValidationStrategy(),
                            new JpegAiModelIconImageValidationStrategy(),
                            new WebpAiModelIconImageValidationStrategy()));

    @Test
    void acceptsStaticPathGradientClipAndLocalUse() {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
                  <defs>
                    <linearGradient id="g"><stop offset="0" stop-color="#10a37f"/></linearGradient>
                    <path id="mark" d="M2 2h28v28H2z"/>
                    <clipPath id="clip"><circle cx="16" cy="16" r="15"/></clipPath>
                  </defs>
                  <g clip-path="url(#clip)" fill="url(#g)"><use href="#mark"/></g>
                </svg>
                """;

        var result = strategy.validate(
                svg.getBytes(StandardCharsets.UTF_8),
                "image/svg+xml; charset=utf-8");

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.SVG);
        assertThat(result.width()).isEqualTo(32);
        assertThat(result.height()).isEqualTo(32);
        assertThat(result.frameCount()).isEqualTo(1);
        assertThat(new String(result.storageBytes(), StandardCharsets.UTF_8))
                .contains("<svg")
                .doesNotContain("<!DOCTYPE", "<script");
    }

    @Test
    void rejectsExecutableExternalAndEmbeddedContent() {
        for (String dangerous : new String[] {
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><script>alert(1)</script></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\" onload=\"alert(1)\"/>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><foreignObject/></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><image href=\"https://example.test/x.png\"/></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><style>@import url(https://example.test/x.css)</style></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><use href=\"data:image/svg+xml,x\"/></svg>",
                "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"/>"
        }) {
            assertUnsafe(dangerous);
        }
    }

    @Test
    void acceptsTrustedOfficialDarkModeStyle() {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg"
                     width="180" height="180" viewBox="0 0 180 180" fill="none">
                  <style>
                    :root { fill: #000; }
                    @media (prefers-color-scheme: dark) {
                      :root { fill: #fff; }
                    }
                  </style>
                  <path d="M10 10h160v160H10z"/>
                </svg>
                """;

        var result = strategy.validate(
                svg.getBytes(StandardCharsets.UTF_8),
                "image/svg+xml",
                AiModelIconImageValidationContext.trustedOfficial(
                        AiModelIconVendor.OPENAI));

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.SVG);
        assertThat(result.width()).isEqualTo(180);
        assertThat(result.height()).isEqualTo(180);
    }

    @Test
    void acceptsTrustedOfficialClassInlineStyleAndEmbeddedJpeg()
            throws Exception {
        String dataUri = jpegDataUri();
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg"
                     xmlns:xlink="http://www.w3.org/1999/xlink"
                     viewBox="0 0 32 32">
                  <style>.st0 { fill: none; }</style>
                  <defs>
                    <clipPath id="clip"><rect width="32" height="32"/></clipPath>
                  </defs>
                  <g class="st0" style="clip-path:url(#clip)">
                    <image width="2" height="2" xlink:href="%s"/>
                  </g>
                </svg>
                """.formatted(dataUri);

        var result = strategy.validate(
                svg.getBytes(StandardCharsets.UTF_8),
                "image/svg+xml",
                AiModelIconImageValidationContext.trustedOfficial(
                        AiModelIconVendor.GOOGLE));

        assertThat(result.format()).isEqualTo(AiModelIconImageFormat.SVG);
        assertThat(result.width()).isEqualTo(32);
        assertThat(result.height()).isEqualTo(32);
    }

    @Test
    void keepsStyleAndImageRejectedByStrictProfile() throws Exception {
        assertUnsafe("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <style>:root { fill: #000; }</style>
                  <path d="M0 0h16v16H0z"/>
                </svg>
                """);
        assertUnsafe("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <image width="2" height="2" href="%s"/>
                </svg>
                """.formatted(jpegDataUri()));
    }

    @Test
    void trustedProfileStillRejectsExternalImagesAndDangerousCss() {
        for (String dangerous : new String[] {
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <image href="https://example.test/icon.png"/>
                </svg>
                """,
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <style>@import url(https://example.test/icon.css);</style>
                </svg>
                """,
                """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <style>@keyframes spin { to { transform: rotate(1turn); } }</style>
                </svg>
                """
        }) {
            assertThatThrownBy(() -> strategy.validate(
                    dangerous.getBytes(StandardCharsets.UTF_8),
                    "image/svg+xml",
                    AiModelIconImageValidationContext.trustedOfficial(
                            AiModelIconVendor.GOOGLE)))
                    .isInstanceOfSatisfying(
                            AiModelIconException.class,
                            exception -> assertThat(exception.code()).isEqualTo(
                                    AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
        }
    }

    @Test
    void rejectsDuplicateAndMissingLocalReferences() {
        assertUnsafe("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <defs>
                    <linearGradient id="same"/>
                    <clipPath id="same"><rect width="16" height="16"/></clipPath>
                  </defs>
                </svg>
                """);
        assertUnsafe("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <path fill="url(#missing)" d="M0 0h16v16H0z"/>
                </svg>
                """);
        assertTrustedUnsafe("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <style id="styleTarget">:root { fill: #000; }</style>
                  <use href="#styleTarget"/>
                </svg>
                """);
    }

    @Test
    void rejectsAggregateCssTextLargerThanThirtyTwoKib() {
        String padding = " ".repeat(17 * 1024);
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16">
                  <style>:root { fill: #000; }%s</style>
                  <style>.mark { fill: #fff; }%s</style>
                  <path class="mark" d="M0 0h16v16H0z"/>
                </svg>
                """.formatted(padding, padding);

        assertThatThrownBy(() -> strategy.validate(
                svg.getBytes(StandardCharsets.UTF_8),
                "image/svg+xml",
                AiModelIconImageValidationContext.trustedOfficial(
                        AiModelIconVendor.OPENAI)))
                .isInstanceOfSatisfying(
                        AiModelIconException.class,
                        exception -> assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }

    @Test
    void rejectsMoreThanEightEmbeddedImages() throws Exception {
        StringBuilder images = new StringBuilder();
        for (int index = 0; index < 9; index++) {
            images.append("<image width=\"2\" height=\"2\" href=\"")
                    .append(jpegDataUri())
                    .append("\"/>");
        }
        assertTrustedUnsafe("""
                <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
                  %s
                </svg>
                """.formatted(images));
    }

    @Test
    void rejectsMissingOrOversizedDisplayBounds() {
        assertUnsafe("<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0\"/></svg>");
        assertUnsafe("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 4097 10\"/>");
    }

    @Test
    void rejectsElementCountAndDepthOverflow() {
        StringBuilder deep = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\">");
        for (int index = 0; index < 64; index++) {
            deep.append("<g>");
        }
        for (int index = 0; index < 64; index++) {
            deep.append("</g>");
        }
        deep.append("</svg>");
        assertUnsafe(deep.toString());

        StringBuilder many = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\">");
        for (int index = 0; index < 2048; index++) {
            many.append("<path d=\"M0 0\"/>");
        }
        many.append("</svg>");
        assertUnsafe(many.toString());
    }

    @Test
    void rejectsMoreThanThirtyTwoAttributesOnOneElement() {
        String[] names = {
                "id", "x", "y", "x1", "y1", "x2", "y2", "cx", "cy", "r", "rx",
                "ry", "fx", "fy", "fr", "d", "points", "transform", "opacity",
                "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width",
                "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
                "stroke-dasharray", "stroke-dashoffset", "stroke-opacity",
                "clip-rule", "offset", "stop-opacity"
        };
        StringBuilder svg = new StringBuilder(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><path");
        for (String name : names) {
            svg.append(' ').append(name).append("=\"1\"");
        }
        svg.append("/></svg>");

        assertUnsafe(svg.toString());
    }

    private void assertUnsafe(String svg) {
        assertThatThrownBy(() -> strategy.validate(
                svg.getBytes(StandardCharsets.UTF_8),
                "image/svg+xml"))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }

    private void assertTrustedUnsafe(String svg) {
        assertThatThrownBy(() -> strategy.validate(
                svg.getBytes(StandardCharsets.UTF_8),
                "image/svg+xml",
                AiModelIconImageValidationContext.trustedOfficial(
                        AiModelIconVendor.OPENAI)))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }

    private static String jpegDataUri() throws Exception {
        BufferedImage image =
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        return "data:image/jpeg;base64,"
                + Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
