package com.example.temperate.service.user.aiconversation.model.stream.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent.Activity;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent.Chunk;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent.ReasoningSummaryDelta;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 验证 OpenAI Responses 原生事件只被转换为项目允许公开的模型事件，不暴露原始供应商对象。
 */
final class OpenAiResponsesEventMapperTest {

    private final OpenAiResponsesEventMapper mapper =
            new OpenAiResponsesEventMapper(new ObjectMapper());

    @Test
    void mapsSearchReasoningSourceTextAndTerminalUsage() {
        assertThat(mapper.map(event("response.web_search_call.searching", """
                {"type":"response.web_search_call.searching","item_id":"ws_1",
                 "item":{"action":{"query":"Spring AI streaming"}}}
                """)))
                .singleElement()
                .isInstanceOfSatisfying(Activity.class, activity -> {
                    assertThat(activity.activityId()).isEqualTo("ws_1");
                    assertThat(activity.phase().name()).isEqualTo("WEB_SEARCH");
                    assertThat(activity.query()).isEqualTo("Spring AI streaming");
                });

        assertThat(mapper.map(event("response.reasoning_summary_text.delta", """
                {"type":"response.reasoning_summary_text.delta","item_id":"rs_1",
                 "delta":"正在整理来源"}
                """)))
                .singleElement()
                .isInstanceOfSatisfying(ReasoningSummaryDelta.class,
                        summary -> assertThat(summary.textDelta())
                                .isEqualTo("正在整理来源"));

        assertThat(mapper.map(event("response.output_item.done", """
                {"type":"response.output_item.done","item":{"id":"ws_1",
                 "type":"web_search_call","action":{"sources":[
                   {"type":"url","title":"OpenAI Docs","url":"https://openai.com/docs"}
                 ]}}}
                """)))
                .anySatisfy(value -> assertThat(value)
                        .isInstanceOfSatisfying(Source.class, source -> {
                            assertThat(source.domain()).isEqualTo("openai.com");
                            assertThat(source.role().name()).isEqualTo("CONSULTED");
                        }));

        assertThat(mapper.map(event("response.output_text.delta", """
                {"type":"response.output_text.delta","delta":"答案"}
                """)))
                .singleElement()
                .isInstanceOfSatisfying(Chunk.class,
                        chunk -> assertThat(chunk.value().text()).isEqualTo("答案"));

        assertThat(mapper.map(event("response.completed", """
                {"type":"response.completed","response":{"id":"resp_1","status":"completed",
                 "usage":{"input_tokens":10,"output_tokens":20,
                   "input_tokens_details":{"cached_tokens":2},
                   "output_tokens_details":{"reasoning_tokens":4}}}}
                """)))
                .anySatisfy(value -> assertThat(value)
                        .isInstanceOfSatisfying(Chunk.class, chunk -> {
                            assertThat(chunk.value().usage().promptTokens()).isEqualTo(10);
                            assertThat(chunk.value().usage().cachedPromptTokens()).isEqualTo(2);
                            assertThat(chunk.value().finishReason()).isEqualTo("STOP");
                            assertThat(chunk.value().upstreamRequestId()).isEqualTo("resp_1");
                        }));
    }

    @Test
    void ignoresUnknownEventsAndRejectsUnsafeSourceSchemes() {
        assertThat(mapper.map(event("response.future.event", """
                {"type":"response.future.event","payload":"ignored"}
                """))).isEmpty();
        assertThat(mapper.map(event("response.output_item.done", """
                {"type":"response.output_item.done","item":{"id":"ws_2",
                 "action":{"sources":[{"title":"bad","url":"javascript:alert(1)"}]}}}
                """))).noneMatch(Source.class::isInstance);
    }

    @Test
    void replacesOversizedProviderIdentifiersWithBoundedStableIdentifiers() {
        String oversized = "x".repeat(2_048);

        assertThat(mapper.map(event("response.web_search_call.searching", """
                {"type":"response.web_search_call.searching","item_id":"%s"}
                """.formatted(oversized))))
                .singleElement()
                .isInstanceOfSatisfying(Activity.class, activity -> {
                    assertThat(activity.activityId()).hasSizeLessThanOrEqualTo(128);
                    assertThat(activity.activityId()).isNotEqualTo(oversized);
                });

        assertThat(mapper.map(event("response.output_item.done", """
                {"type":"response.output_item.done","item":{"id":"%s",
                 "type":"web_search_call","action":{"sources":[
                   {"id":"%s","title":"OpenAI Docs","url":"https://openai.com/docs"}
                 ]}}}
                """.formatted(oversized, oversized)))
                .filteredOn(Source.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(Source.class, source -> {
                    assertThat(source.activityId()).hasSizeLessThanOrEqualTo(128);
                    assertThat(source.sourceId()).hasSizeLessThanOrEqualTo(128);
                    assertThat(source.sourceId()).isNotEqualTo(oversized);
                });
    }

    private static OpenAiResponsesSseEvent event(String name, String data) {
        return new OpenAiResponsesSseEvent(name, data);
    }
}
