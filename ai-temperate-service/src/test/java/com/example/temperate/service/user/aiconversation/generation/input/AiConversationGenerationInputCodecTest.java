package com.example.temperate.service.user.aiconversation.generation.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfile;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.response.AiConversationWebSearchMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Generation 输入 JSONB 兼容旧附件数组和旧图片档位快照，且信封永远不承载图片内容。
 */
final class AiConversationGenerationInputCodecTest {

    private final AiConversationGenerationInputCodec codec =
            new AiConversationGenerationInputCodec(new ObjectMapper());

    @Test
    void readsLegacyAttachmentArrayWithoutImageOptions() {
        AiConversationGenerationInputSnapshot snapshot = codec.decode("[]");

        assertThat(snapshot.attachments()).isEmpty();
        assertThat(snapshot.imageGeneration()).isNull();
    }

    @Test
    void writesAndReadsVersionedImageEnvelopeWithoutBase64() {
        AiConversationImageGenerationOptions options =
                AiConversationImageGenerationOptions.from(
                        AiConversationImageAspect.LANDSCAPE,
                        new AiConversationImageProfile(
                                AiConversationImageQuality.HIGH,
                                1536,
                                1024,
                                AiConversationReasoningEffort.MEDIUM));

        String json = codec.encode(List.of(), options);
        AiConversationGenerationInputSnapshot decoded = codec.decode(json);

        assertThat(json)
                .contains("\"schemaVersion\":4")
                .contains("\"webSearchMode\":\"OFF\"")
                .contains("\"kind\":\"IMAGE\"")
                .contains("\"action\":\"GENERATE\"")
                .contains("\"outputCount\":1")
                .contains("\"profileVersion\":\"image-v2\"")
                .contains("\"size\":\"1536x1024\"")
                .doesNotContain("b64_json", "base64", "bytes");
        assertThat(decoded.attachments()).isEmpty();
        assertThat(decoded.imageGeneration()).isEqualTo(options);
    }

    @Test
    void readsLegacyImageV1SnapshotForQueuedGenerationCompatibility() {
        AiConversationGenerationInputSnapshot snapshot = codec.decode("""
                {
                  "schemaVersion": 2,
                  "attachments": [],
                  "generation": {
                    "kind": "IMAGE",
                    "profileVersion": "image-v1",
                    "aspect": "LANDSCAPE",
                    "quality": "HIGH",
                    "width": 2560,
                    "height": 1440,
                    "size": "2560x1440",
                    "reasoningEffort": "HIGH",
                    "format": "webp",
                    "compression": 90,
                    "partialImages": 3
                  }
                }
                """);

        assertThat(snapshot.imageGeneration().profileVersion())
                .isEqualTo("image-v1");
        assertThat(snapshot.imageGeneration().aspect())
                .isEqualTo(AiConversationImageAspect.LANDSCAPE);
        assertThat(snapshot.imageGeneration().size())
                .isEqualTo("2560x1440");
        assertThat(snapshot.imageGeneration().action())
                .isEqualTo(AiConversationImageAction.GENERATE);
        assertThat(snapshot.imageGeneration().outputCount()).isEqualTo((short) 1);
    }

    @Test
    void roundTripsEditActionAndMultipleOutputCount() {
        AiConversationImageGenerationOptions options =
                AiConversationImageGenerationOptions.from(
                        AiConversationImageAspect.PORTRAIT,
                        new AiConversationImageProfile(
                                AiConversationImageQuality.MEDIUM,
                                1024,
                                1536,
                                AiConversationReasoningEffort.MEDIUM),
                        AiConversationImageAction.EDIT,
                        (short) 10);

        String json = codec.encode(List.of(), options);

        assertThat(json)
                .contains("\"action\":\"EDIT\"")
                .contains("\"outputCount\":10")
                .doesNotContain("signed", "base64", "b64_json", "bytes");
        assertThat(codec.decode(json).imageGeneration()).isEqualTo(options);
    }

    @Test
    void rejectsUnknownEnvelopeVersion() {
        assertThatThrownBy(() -> codec.decode("""
                {"schemaVersion":99,"attachments":[]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version");
    }

    @Test
    void freezesRequiredWebSearchWithoutImageOptions() {
        String json = codec.encode(
                List.of(),
                null,
                AiConversationWebSearchMode.REQUIRED);

        AiConversationGenerationInputSnapshot snapshot = codec.decode(json);

        assertThat(snapshot.imageGeneration()).isNull();
        assertThat(snapshot.webSearchMode())
                .isEqualTo(AiConversationWebSearchMode.REQUIRED);
        assertThat(json).contains("\"webSearchMode\":\"REQUIRED\"");
    }

    @Test
    void rejectsFractionalOutputCountInFrozenEnvelope() {
        AiConversationImageGenerationOptions options =
                AiConversationImageGenerationOptions.from(
                        AiConversationImageAspect.SQUARE,
                        new AiConversationImageProfile(
                                AiConversationImageQuality.LOW,
                                1024,
                                1024,
                                AiConversationReasoningEffort.LOW));
        String malformed = codec.encode(List.of(), options)
                .replace("\"outputCount\":1", "\"outputCount\":1.5");

        assertThatThrownBy(() -> codec.decode(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputCount");
    }
}
