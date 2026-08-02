package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationCancelRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationDetachCheck;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationEnvelope;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationRequested;
import com.example.temperate.service.user.aiconversation.generation.rabbit.AiConversationGenerationTerminated;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证 RabbitMQ 只承载生成标识、控制命令与事实终态，不携带正文、原始幂等键或资金指令。
 */
class AiConversationGenerationMessageContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void envelopeContainsRequiredReliableMessageFields() throws Exception {
        AiConversationGenerationEnvelope<AiConversationGenerationRequested> envelope =
                AiConversationGenerationEnvelope.of(
                        UUID.fromString("a6c1e736-fd73-42c5-8e55-c846db66a652"),
                        "AI_GENERATION_REQUESTED",
                        OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC),
                        "trace-safe",
                        new AiConversationGenerationRequested(
                                "AZ-vpV3kfag70-0EMMUETQ",
                                "AZ-vpV3kfag70-0EMMUETg"));

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json)
                .contains("messageId", "eventType", "schemaVersion", "occurredAt", "traceId")
                .doesNotContain("inputText", "assistantText", "idempotencyKey", "refund");
    }

    @Test
    void definesControlDetachAndTerminalFactsWithoutMoneyInstructions() throws Exception {
        Object[] payloads = {
                new AiConversationGenerationCancelRequested(
                        "AZ-vpV3kfag70-0EMMUETQ", "USER_STOP", 1),
                new AiConversationGenerationDetachCheck(
                        "AZ-vpV3kfag70-0EMMUETQ",
                        7,
                        OffsetDateTime.of(2026, 8, 1, 10, 0, 0, 0, ZoneOffset.UTC)),
                new AiConversationGenerationTerminated(
                        "AZ-vpV3kfag70-0EMMUETQ",
                        "AZ-vpV3kfag70-0EMMUETg",
                        "UPSTREAM_FAILED",
                        "AI_UPSTREAM_STREAM_FAILED",
                        1)
        };

        for (Object payload : payloads) {
            assertThat(objectMapper.writeValueAsString(payload))
                    .doesNotContain("REFUND_FULL", "REFUND_REQUESTED", "balance", "redisKey");
        }
    }
}
