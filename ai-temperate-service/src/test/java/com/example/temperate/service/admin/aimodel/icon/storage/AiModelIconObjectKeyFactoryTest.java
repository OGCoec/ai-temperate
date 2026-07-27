package com.example.temperate.service.admin.aimodel.icon.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import org.junit.jupiter.api.Test;

/**
 * 验证模型图标 Object Key 只由固定前缀、安全名称和真实图片格式组成。
 */
final class AiModelIconObjectKeyFactoryTest {

    private final AiModelIconObjectKeyFactory factory =
            new AiModelIconObjectKeyFactory("ai-temperate/models/icons/");

    @Test
    void createsStableAsciiSlugWithoutRandomIdentifier() {
        assertThat(factory.create(" OpenAI GPT ", AiModelIconImageFormat.PNG))
                .isEqualTo("ai-temperate/models/icons/openai-gpt.png");
        assertThat(factory.create("OpenAI", AiModelIconImageFormat.SVG))
                .isEqualTo("ai-temperate/models/icons/openai.svg");
        assertThat(factory.create("OpenAI", AiModelIconImageFormat.GIF))
                .isEqualTo("ai-temperate/models/icons/openai.gif");
        assertThat(factory.create("OpenAI", AiModelIconImageFormat.ICO))
                .isEqualTo("ai-temperate/models/icons/openai.ico");
        assertThat(factory.create("OpenAI", AiModelIconImageFormat.AVIF))
                .isEqualTo("ai-temperate/models/icons/openai.avif");
    }

    @Test
    void rejectsNameThatCannotProduceSafeAsciiSlug() {
        assertThatThrownBy(() -> factory.create("图标", AiModelIconImageFormat.PNG))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
