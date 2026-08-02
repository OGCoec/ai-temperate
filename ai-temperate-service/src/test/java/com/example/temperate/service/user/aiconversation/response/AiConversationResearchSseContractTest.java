package com.example.temperate.service.user.aiconversation.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证研究状态、来源和推理摘要使用独立稳定事件名，并保留同一流中的顺序号。
 */
final class AiConversationResearchSseContractTest {

    @Test
    void exposesStableResearchEventNamesAndSequences() {
        AiConversationStreamEvent activity = AiConversationStreamEvent.activity(
                new AiConversationActivityData(
                        1L,
                        "search-1",
                        "WEB_SEARCH",
                        "IN_PROGRESS",
                        "safe query",
                        "2026-08-02T00:00:00Z"));
        AiConversationStreamEvent source = AiConversationStreamEvent.source(
                new AiConversationSourceData(
                        2L,
                        "search-1",
                        "source-1",
                        "OpenAI",
                        "https://openai.com/",
                        "openai.com",
                        "CONSULTED",
                        "2026-08-02T00:00:01Z"));
        AiConversationStreamEvent summary =
                AiConversationStreamEvent.reasoningSummary(
                        new AiConversationReasoningSummaryData(
                                3L,
                                "reasoning-1",
                                "正在整理来源",
                                "2026-08-02T00:00:02Z"));

        assertThat(activity.name()).isEqualTo("activity");
        assertThat(source.name()).isEqualTo("source");
        assertThat(summary.name()).isEqualTo("reasoning_summary");
        assertThat(((AiConversationActivityData) activity.data()).sequence())
                .isEqualTo(1L);
        assertThat(((AiConversationSourceData) source.data()).sequence())
                .isEqualTo(2L);
        assertThat(((AiConversationReasoningSummaryData) summary.data()).sequence())
                .isEqualTo(3L);
    }
}
