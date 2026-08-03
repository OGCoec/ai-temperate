package com.example.temperate.service.user.aiconversation.response.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityPhase;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationActivityStatus;
import com.example.temperate.service.user.aiconversation.model.stream.AiConversationModelEvent;
import org.junit.jupiter.api.Test;

/**
 * 验证模型活动事件只按精确业务内容去重，同时保留不同状态、查询和活动标识的真实历史。
 */
final class AiConversationActivityEventDeduplicatorTest {

    @Test
    void rejectsExactDuplicatesAndKeepsDistinctStatuses() {
        AiConversationActivityEventDeduplicator deduplicator =
                new AiConversationActivityEventDeduplicator();
        AiConversationModelEvent.Activity started = activity(
                "ws-1", AiConversationActivityStatus.STARTED, "Java Thread");
        AiConversationModelEvent.Activity inProgress = activity(
                "ws-1", AiConversationActivityStatus.IN_PROGRESS, "Java Thread");
        AiConversationModelEvent.Activity completed = activity(
                "ws-1", AiConversationActivityStatus.COMPLETED, "Java Thread");

        String startedEventId = deduplicator.accept(started);

        assertThat(startedEventId).matches("^act_v1_[A-Za-z0-9_-]{43}$");
        assertThat(deduplicator.accept(started)).isNull();
        assertThat(deduplicator.accept(inProgress)).isNotNull()
                .isNotEqualTo(startedEventId);
        assertThat(deduplicator.accept(completed)).isNotNull()
                .isNotEqualTo(startedEventId);
    }

    @Test
    void keepsDifferentQueriesActivityIdsAndPhases() {
        AiConversationActivityEventDeduplicator deduplicator =
                new AiConversationActivityEventDeduplicator();

        assertThat(deduplicator.accept(activity(
                "ws-1", AiConversationActivityStatus.IN_PROGRESS, "query-a")))
                .isNotNull();
        assertThat(deduplicator.accept(activity(
                "ws-1", AiConversationActivityStatus.IN_PROGRESS, "query-b")))
                .isNotNull();
        assertThat(deduplicator.accept(activity(
                "ws-2", AiConversationActivityStatus.IN_PROGRESS, "query-a")))
                .isNotNull();
        assertThat(deduplicator.accept(new AiConversationModelEvent.Activity(
                "ws-1",
                AiConversationActivityPhase.REASONING,
                AiConversationActivityStatus.IN_PROGRESS,
                "query-a")))
                .isNotNull();
    }

    @Test
    void generatesTheSameEventIdAcrossRequestScopedInstances() {
        AiConversationModelEvent.Activity value = activity(
                "ws-1", AiConversationActivityStatus.COMPLETED, "same-query");

        String first = new AiConversationActivityEventDeduplicator()
                .accept(value);
        String second = new AiConversationActivityEventDeduplicator()
                .accept(value);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void keepsNullAndEmptyQueryDistinct() {
        AiConversationActivityEventDeduplicator deduplicator =
                new AiConversationActivityEventDeduplicator();

        assertThat(deduplicator.accept(activity(
                "ws-1", AiConversationActivityStatus.STARTED, null)))
                .isNotNull();
        assertThat(deduplicator.accept(activity(
                "ws-1", AiConversationActivityStatus.STARTED, "")))
                .isNotNull();
    }

    @Test
    void boundsUniqueActivityEventsToTheFrontendLimit() {
        AiConversationActivityEventDeduplicator deduplicator =
                new AiConversationActivityEventDeduplicator();

        for (int index = 0; index < 500; index++) {
            assertThat(deduplicator.accept(activity(
                    "ws-" + index,
                    AiConversationActivityStatus.IN_PROGRESS,
                    "query")))
                    .isNotNull();
        }

        assertThat(deduplicator.accept(activity(
                "ws-overflow",
                AiConversationActivityStatus.IN_PROGRESS,
                "query")))
                .isNull();
    }

    private static AiConversationModelEvent.Activity activity(
            String activityId,
            AiConversationActivityStatus status,
            String query) {
        return new AiConversationModelEvent.Activity(
                activityId,
                AiConversationActivityPhase.WEB_SEARCH,
                status,
                query);
    }
}
