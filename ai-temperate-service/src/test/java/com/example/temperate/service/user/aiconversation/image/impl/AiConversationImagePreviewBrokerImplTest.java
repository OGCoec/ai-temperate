package com.example.temperate.service.user.aiconversation.image.impl;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePersistedData;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewBroker;
import com.example.temperate.service.user.aiconversation.image.AiConversationGeneratedImagePhase;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewKind;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageOutputStatusData;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewData;
import com.example.temperate.service.user.aiconversation.image.AiConversationImagePreviewPublishResult;
import com.example.temperate.service.user.aiconversation.image.AiConversationPreparedImagePreview;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

/**
 * 验证观察者重连时只按输出序号重放每个槽位的最新预览，并继续接收实时事件。
 */
final class AiConversationImagePreviewBrokerImplTest {

    @Test
    void replaysLatestPreviewPerOutputIndexInStableOrder() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties());
        broker.publish("generation", image((short) 1, (short) 0, (byte) 1));
        broker.publish("generation", image((short) 0, (short) 0, (byte) 2));
        broker.publish("generation", image((short) 1, (short) 1, (byte) 3));

        StepVerifier.create(broker.events("generation").take(2))
                .assertNext(event -> {
                    AiConversationImagePreviewData data =
                            (AiConversationImagePreviewData) event.data();
                    org.assertj.core.api.Assertions.assertThat(data.outputIndex())
                            .isZero();
                })
                .assertNext(event -> {
                    AiConversationImagePreviewData data =
                            (AiConversationImagePreviewData) event.data();
                    org.assertj.core.api.Assertions.assertThat(data.outputIndex())
                            .isEqualTo((short) 1);
                    org.assertj.core.api.Assertions.assertThat(data.partialImageIndex())
                            .isEqualTo((short) 1);
                })
                .verifyComplete();
    }

    @Test
    void failureReplacesOnlyItsOwnLatestPreview() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties());
        broker.publish("generation", image((short) 0, (short) 0, (byte) 1));
        broker.publish("generation", image((short) 1, (short) 0, (byte) 2));
        broker.publishFailure(
                "generation", (short) 0, "AI_UPSTREAM_STREAM_FAILED");

        StepVerifier.create(broker.events("generation").take(2))
                .assertNext(event -> {
                    AiConversationImageOutputStatusData data =
                            (AiConversationImageOutputStatusData) event.data();
                    org.assertj.core.api.Assertions.assertThat(data.outputIndex())
                            .isZero();
                    org.assertj.core.api.Assertions.assertThat(data.status())
                            .isEqualTo("FAILED");
                })
                .assertNext(event -> {
                    AiConversationImagePreviewData data =
                            (AiConversationImagePreviewData) event.data();
                    org.assertj.core.api.Assertions.assertThat(data.outputIndex())
                            .isEqualTo((short) 1);
                })
                .verifyComplete();
    }

    @Test
    void retainedBytesReturnToZeroAfterReplaceFailureAndRelease() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties(), 20L);

        broker.publish("generation-a", image((short) 0, (short) 0, (byte) 1));
        org.assertj.core.api.Assertions.assertThat(broker.retainedBytes()).isEqualTo(12L);
        broker.publish("generation-a", image((short) 0, (short) 1, (byte) 2));
        org.assertj.core.api.Assertions.assertThat(broker.retainedBytes()).isEqualTo(12L);
        broker.publish("generation-b", image((short) 0, (short) 0, (byte) 3));
        org.assertj.core.api.Assertions.assertThat(broker.retainedBytes()).isEqualTo(12L);

        broker.publishFailure("generation-a", (short) 0, "AI_UPSTREAM_STREAM_FAILED");
        org.assertj.core.api.Assertions.assertThat(broker.retainedBytes()).isZero();
        broker.release("generation-a");
        broker.release("generation-b");
        org.assertj.core.api.Assertions.assertThat(broker.retainedBytes()).isZero();
    }

    @Test
    void reportsLiveObserverAndReplayRetention() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties());
        Disposable observer = broker.events("generation").subscribe();
        try {
            AiConversationImagePreviewPublishResult result = broker.publish(
                    "generation", image((short) 0, (short) 0, (byte) 1));

            org.assertj.core.api.Assertions.assertThat(result.accepted()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.retained()).isTrue();
            org.assertj.core.api.Assertions.assertThat(result.observerCount()).isOne();
        } finally {
            observer.dispose();
            broker.release("generation");
        }
    }

    @Test
    void reportsLiveDeliveryWhenReplayRetentionLimitIsFull() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties(), 12L);
        broker.publish("generation-a", image((short) 0, (short) 0, (byte) 1));

        AiConversationImagePreviewPublishResult result = broker.publish(
                "generation-b", image((short) 0, (short) 0, (byte) 2));

        org.assertj.core.api.Assertions.assertThat(result.accepted()).isTrue();
        org.assertj.core.api.Assertions.assertThat(result.retained()).isFalse();
        org.assertj.core.api.Assertions.assertThat(result.observerCount()).isZero();
        broker.release("generation-a");
        broker.release("generation-b");
    }

    @Test
    void persistedImageReplacesPreviewAndReleasesRetainedBytes() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties(), 20L);
        broker.publish("generation", image((short) 0, (short) 0, (byte) 1));

        broker.publishPersisted("generation", (short) 0, persisted());

        org.assertj.core.api.Assertions.assertThat(broker.retainedBytes()).isZero();
        StepVerifier.create(broker.events("generation").take(1))
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.name())
                            .isEqualTo("image-persisted");
                    AiConversationImagePersistedData data =
                            (AiConversationImagePersistedData) event.data();
                    org.assertj.core.api.Assertions.assertThat(data.outputIndex()).isZero();
                    org.assertj.core.api.Assertions.assertThat(data.attachment().url())
                            .isEqualTo("https://public-oss.example.test/generated.webp");
                })
                .verifyComplete();
    }

    @Test
    void failureAfterPersistenceBecomesTheOnlyReplayStateForItsSlot() {
        AiConversationImagePreviewBrokerImpl broker =
                new AiConversationImagePreviewBrokerImpl(properties(), 20L);
        broker.publishPersisted("generation", (short) 0, persisted());

        broker.publishFailure(
                "generation", (short) 0, "AI_UPSTREAM_STREAM_FAILED");

        StepVerifier.create(broker.events("generation").take(1))
                .assertNext(event -> {
                    org.assertj.core.api.Assertions.assertThat(event.name())
                            .isEqualTo("image-output-status");
                    AiConversationImageOutputStatusData data =
                            (AiConversationImageOutputStatusData) event.data();
                    org.assertj.core.api.Assertions.assertThat(data.outputIndex())
                            .isZero();
                    org.assertj.core.api.Assertions.assertThat(data.status())
                            .isEqualTo("FAILED");
                })
                .verifyComplete();
    }

    @Test
    void noOpBrokerAcceptsPersistedEventsWithoutCreatingReplayState() {
        AiConversationImagePreviewBroker broker =
                AiConversationImagePreviewBroker.noOp();

        broker.publishPersisted("generation", (short) 0, persisted());

        StepVerifier.create(broker.events("generation"))
                .verifyComplete();
    }

    private static AiConversationPreparedImagePreview image(
            short outputIndex,
            short partialIndex,
            byte marker) {
        return new AiConversationPreparedImagePreview(
                "image-" + outputIndex,
                AiConversationGeneratedImagePhase.PARTIAL,
                outputIndex,
                partialIndex,
                "image/webp",
                1024,
                1024,
                AiConversationImagePreviewKind.THUMBNAIL,
                true,
                new byte[] {'R', 'I', 'F', 'F', marker, 0, 0, 0, 'W', 'E', 'B', 'P'});
    }

    private static AiConversationAttachment persisted() {
        return AiConversationAttachment.available(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL",
                "generated-1.webp",
                "image/webp",
                12L,
                AiConversationAttachmentCategory.IMAGE,
                "https://public-oss.example.test/generated.webp");
    }

    private static AiConversationAsyncGenerationProperties properties() {
        return new AiConversationAsyncGenerationProperties(
                true,
                "test",
                Duration.ofSeconds(30),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5),
                1,
                Duration.ofMinutes(2));
    }
}
