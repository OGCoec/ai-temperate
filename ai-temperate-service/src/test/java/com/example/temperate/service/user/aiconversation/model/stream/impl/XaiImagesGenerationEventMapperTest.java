package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.image.AiConversationImageAction;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageGenerationOptions;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageMeteringStatus;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageQuality;
import com.example.temperate.service.user.aiconversation.model.AiConversationProviderCostUsage;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 xAI 图片响应只接受整数成本 ticks，并在成本缺失时保留图片和安全待对账证据。
 */
final class XaiImagesGenerationEventMapperTest {

    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final XaiImagesGenerationEventMapper mapper =
            new XaiImagesGenerationEventMapper(1024);

    @Test
    void mapsIntegralCostToProviderCostUsage() throws Exception {
        var root = objectMapper.readTree("""
                {"request_id":"req-safe","data":[{"b64_json":"%s"}],
                 "usage":{"cost_in_usd_ticks":200000000}}
                """.formatted(Base64.getEncoder().encodeToString(PNG)));

        var events = mapper.map(root, options(), (short) 0);

        assertThat(events).hasSize(2);
        AiConversationModelEvent.ImageUsage usage =
                (AiConversationModelEvent.ImageUsage) events.get(1);
        assertThat(usage.usage()).isEqualTo(
                new AiConversationProviderCostUsage(200_000_000L));
    }

    @Test
    void keepsImageAndEmitsMissingCostEvidence() throws Exception {
        var root = objectMapper.readTree("""
                {"request_id":"req-safe","data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(PNG)));

        var events = mapper.map(root, options(), (short) 0);

        assertThat(events.get(0)).isInstanceOf(AiConversationModelEvent.Image.class);
        AiConversationModelEvent.ImageCostEvidence evidence =
                (AiConversationModelEvent.ImageCostEvidence) events.get(1);
        assertThat(evidence.evidence().status())
                .isEqualTo(AiConversationImageMeteringStatus.MISSING_COST);
        assertThat(evidence.evidence().costTicks()).isNull();
    }

    @Test
    void rejectsFloatingAndStringCostsAsInvalidEvidence() throws Exception {
        for (String cost : new String[] {
                "1.5", "\"200000000\"", "-1", "9223372036854775808"}) {
            var root = objectMapper.readTree("""
                    {"data":[{"b64_json":"%s"}],
                     "usage":{"cost_in_usd_ticks":%s}}
                    """.formatted(Base64.getEncoder().encodeToString(PNG), cost));

            AiConversationModelEvent.ImageCostEvidence evidence =
                    (AiConversationModelEvent.ImageCostEvidence)
                            mapper.map(root, options(), (short) 0).get(1);
            assertThat(evidence.evidence().status())
                    .isEqualTo(AiConversationImageMeteringStatus.INVALID_COST);
        }
    }

    private static AiConversationImageGenerationOptions options() {
        return new AiConversationImageGenerationOptions(
                AiConversationImageGenerationOptions.CURRENT_PROFILE_VERSION,
                AiConversationImageAspect.SQUARE,
                AiConversationImageQuality.LOW,
                1024,
                1024,
                AiConversationReasoningEffort.LOW,
                "webp",
                90,
                0,
                AiConversationImageAction.GENERATE,
                (short) 1);
    }
}
