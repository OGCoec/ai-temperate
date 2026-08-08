package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 xAI 五种视频操作通过不可变枚举注册表选择，重复和缺失实现都会受控失败。
 */
final class XaiVideoOperationStrategyRegistryTest {

    @Test
    void selectsStrategyByStableMode() {
        XaiVideoOperationStrategy text = strategy(AiConversationVideoMode.TEXT_TO_VIDEO);
        XaiVideoOperationStrategyRegistry registry =
                new XaiVideoOperationStrategyRegistry(completeStrategies(text));

        assertThat(registry.getRequired(AiConversationVideoMode.TEXT_TO_VIDEO))
                .isSameAs(text);
    }

    @Test
    void rejectsDuplicateModesDuringConstruction() {
        assertThatThrownBy(() -> new XaiVideoOperationStrategyRegistry(Map.of(
                "first", strategy(AiConversationVideoMode.TEXT_TO_VIDEO),
                "second", strategy(AiConversationVideoMode.TEXT_TO_VIDEO))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TEXT_TO_VIDEO");
    }

    @Test
    void rejectsMissingModeDuringConstruction() {
        assertThatThrownBy(() -> new XaiVideoOperationStrategyRegistry(Map.of(
                "text", strategy(AiConversationVideoMode.TEXT_TO_VIDEO))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing");
    }

    @Test
    void rejectsUnknownModeWithoutReturningNull() {
        XaiVideoOperationStrategyRegistry registry =
                new XaiVideoOperationStrategyRegistry(completeStrategies(
                        strategy(AiConversationVideoMode.TEXT_TO_VIDEO)));

        assertThatThrownBy(() -> registry.getRequired(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported");
    }

    private static Map<String, XaiVideoOperationStrategy> completeStrategies(
            XaiVideoOperationStrategy text) {
        return Map.of(
                "text", text,
                "image", strategy(AiConversationVideoMode.IMAGE_TO_VIDEO),
                "reference", strategy(AiConversationVideoMode.REFERENCE_TO_VIDEO),
                "edit", strategy(AiConversationVideoMode.VIDEO_EDIT),
                "extend", strategy(AiConversationVideoMode.VIDEO_EXTEND));
    }

    private static XaiVideoOperationStrategy strategy(
            AiConversationVideoMode mode) {
        return new XaiVideoOperationStrategy() {
            @Override
            public AiConversationVideoMode mode() {
                return mode;
            }

            @Override
            public XaiVideoStartRequest buildRequest(
                    XaiVideoOperationContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
