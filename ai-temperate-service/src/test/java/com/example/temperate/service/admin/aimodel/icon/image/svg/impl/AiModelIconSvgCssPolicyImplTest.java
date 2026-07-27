package com.example.temperate.service.admin.aimodel.icon.image.svg.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import org.junit.jupiter.api.Test;

/**
 * 验证可信官方 SVG 的 CSS 兼容档位只接受有限的静态展示规则。
 */
final class AiModelIconSvgCssPolicyImplTest {

    private final AiModelIconSvgCssPolicyImpl policy =
            new AiModelIconSvgCssPolicyImpl();

    @Test
    void acceptsOfficialDarkModeAndGeminiStyleRules() {
        policy.validateStyleSheet("""
                :root { fill: #000; }
                .st0 { fill: none; }
                @media (prefers-color-scheme: dark) {
                  :root { fill: #fff; }
                }
                """);
        policy.validateDeclarationList(
                "clip-path:url(#clip);fill:url(#gradient);"
                        + "overflow:visible;enable-background:new 0 0 32 32");
    }

    @Test
    void rejectsExternalResourcesAnimationAndComplexSelectors() {
        for (String css : new String[] {
                "@import url(https://example.test/icon.css);",
                ".icon { fill: url(https://example.test/icon.svg); }",
                "@keyframes spin { to { transform: rotate(1turn); } }",
                "svg path { fill: #000; }",
                ".icon { background-image: url(data:image/png;base64,AA==); }"
        }) {
            assertUnsafe(() -> policy.validateStyleSheet(css));
        }
    }

    @Test
    void rejectsUnsafeInlineDeclarations() {
        for (String css : new String[] {
                "fill:url(https://example.test/icon.svg)",
                "fill:var(--icon-color)",
                "animation:spin 1s infinite",
                "font-family:url(https://example.test/font.woff2)"
        }) {
            assertUnsafe(() -> policy.validateDeclarationList(css));
        }
    }

    private static void assertUnsafe(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_IMAGE_UNSAFE));
    }
}
