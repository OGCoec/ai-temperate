package com.example.temperate.service.user.aiconversation.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.model.AiConversationModelChunk;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * 验证上游片段立即转发给 SSE，同时 Redis 消费端仍按 UTF-8 字节或时间窗口批量刷新。
 */
final class AiConversationStreamBatcherTest {

    @Test
    void reportsSizeThresholdWhenPersistenceBatchReachesConfiguredBytes() {
        AtomicReference<AiConversationStreamFlushReason> reason = new AtomicReference<>();

        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                        Flux.just(new AiConversationModelChunk(
                                "1234", null, null, null)),
                        4,
                        Duration.ofSeconds(1),
                        (batch, flushReason) -> reason.set(flushReason)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(reason).hasValue(AiConversationStreamFlushReason.SIZE_THRESHOLD);
    }

    @Test
    void doesNotRequestUpstreamBeforeDownstreamDemand() {
        AtomicLong requested = new AtomicLong();
        Flux<AiConversationModelChunk> source = Flux.range(1, 257)
                .map(index -> new AiConversationModelChunk(
                        Integer.toString(index), null, null, null))
                .doOnRequest(requested::addAndGet);

        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                                source,
                                4096,
                                Duration.ofSeconds(30),
                                ignored -> {
                                }),
                        0)
                .then(() -> assertThat(requested).hasValue(0L))
                .thenRequest(1)
                .expectNextMatches(chunk -> chunk.text().equals("1"))
                .then(() -> assertThat(requested).hasValue(1L))
                .thenCancel()
                .verify();
    }

    @Test
    void forwardsTenThousandChunksWithoutLossOrReordering() {
        List<String> delivered = new ArrayList<>();
        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        Flux<AiConversationModelChunk> source = Flux.range(1, 10_000)
                .map(index -> new AiConversationModelChunk(
                        Integer.toString(index), null, null, null));

        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                                source,
                                4096,
                                Duration.ofSeconds(30),
                                batch -> persisted.add(batch))
                        .doOnNext(chunk -> delivered.add(chunk.text())),
                        0)
                .thenRequest(10_000)
                .expectNextCount(10_000)
                .verifyComplete();

        assertThat(delivered)
                .hasSize(10_000)
                .startsWith("1", "2", "3")
                .endsWith("9998", "9999", "10000");
        assertThat(persisted.stream()
                        .flatMap(List::stream)
                        .map(AiConversationModelChunk::text))
                .containsExactlyElementsOf(delivered);
    }

    @Test
    void supportsOneHundredTwentyEightConcurrentSlowStreamsWithoutLoss() {
        AtomicLong persistedChunks = new AtomicLong();
        Flux<AiConversationModelChunk> concurrent = Flux.range(0, 128)
                .flatMap(stream -> AiConversationStreamBatcher.forwardWhileBatching(
                                Flux.range(0, 1000)
                                        .map(index -> new AiConversationModelChunk(
                                                stream + ":" + index,
                                                null,
                                                null,
                                                null))
                                        .subscribeOn(Schedulers.parallel()),
                                4096,
                                Duration.ofSeconds(30),
                                batch -> persistedChunks.addAndGet(batch.size())),
                        128)
                .limitRate(1);

        StepVerifier.create(concurrent)
                .expectNextCount(128_000)
                .verifyComplete();

        assertThat(persistedChunks).hasValue(128_000L);
    }

    @Test
    void forwardsChunkBeforeGivingItToPersistence() {
        AtomicBoolean downstreamObserved = new AtomicBoolean();
        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        AiConversationModelChunk chunk =
                new AiConversationModelChunk("first", null, null, null);

        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                        Flux.just(chunk),
                        4096,
                        Duration.ofSeconds(30),
                        batch -> {
                            assertThat(downstreamObserved).isTrue();
                            persisted.add(batch);
                        }))
                .assertNext(forwarded -> {
                    assertThat(forwarded).isSameAs(chunk);
                    downstreamObserved.set(true);
                })
                .verifyComplete();

        assertThat(persisted).singleElement()
                .satisfies(batch -> assertThat(batch).containsExactly(chunk));
    }

    @Test
    void forwardsImmediatelyAndFlushesPersistenceAtByteBoundary() {
        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        Flux<AiConversationModelChunk> source = Flux.just(
                new AiConversationModelChunk("你你", null, null, null),
                new AiConversationModelChunk("好", null, null, null));

        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                        source,
                        6,
                        Duration.ofSeconds(30),
                        batch -> persisted.add(batch)))
                .expectNextMatches(chunk -> chunk.text().equals("你你"))
                .expectNextMatches(chunk -> chunk.text().equals("好"))
                .verifyComplete();

        assertThat(persisted).hasSize(2);
    }

    @Test
    void flushesAfterMaximumWaitWhenByteBoundaryIsNotReached() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        Flux<AiConversationModelChunk> source = Flux.just(
                new AiConversationModelChunk("a", null, null, null))
                .concatWith(Flux.never());

        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        StepVerifier.withVirtualTime(
                        () -> AiConversationStreamBatcher.forwardWhileBatching(
                                source,
                                4096,
                                Duration.ofMillis(250),
                                batch -> persisted.add(batch),
                                scheduler),
                        () -> scheduler,
                        1)
                .expectNextMatches(chunk -> chunk.text().equals("a"))
                .thenAwait(Duration.ofMillis(250))
                .then(() -> {
                    assertThat(persisted).hasSize(1);
                    assertThat(persisted.getFirst())
                            .singleElement()
                            .extracting(AiConversationModelChunk::text)
                            .isEqualTo("a");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void metadataOnlyChunksCannotGrowOneBatchWithoutBound() {
        AiConversationModelChunk empty =
                new AiConversationModelChunk("", null, null, null);

        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                        Flux.range(0, 1024).map(ignored -> empty),
                        4096,
                        Duration.ofSeconds(30),
                        batch -> persisted.add(batch)))
                .expectNextCount(1024)
                .verifyComplete();
        assertThat(persisted)
                .singleElement()
                .satisfies(batch -> assertThat(batch).hasSize(1024));
    }

    @Test
    void generatedMediaFlushesImmediatelyWithoutWaitingForTextBoundary() {
        AiConversationModelChunk media = new AiConversationModelChunk(
                "",
                null,
                null,
                null,
                List.of(new AiConversationGeneratedMedia(
                        "generated.png",
                        "image/png",
                        new byte[] {1})),
                false);
        AiConversationModelChunk metadata =
                new AiConversationModelChunk("", null, null, null);

        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        StepVerifier.create(AiConversationStreamBatcher.forwardWhileBatching(
                        Flux.just(media, metadata),
                        4096,
                        Duration.ofSeconds(30),
                        batch -> persisted.add(batch)))
                .expectNext(media)
                .expectNext(metadata)
                .verifyComplete();
        assertThat(persisted).hasSize(2);
    }

    @Test
    void cancellationFlushesOnceAndDisposesPeriodicWork() {
        VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
        List<List<AiConversationModelChunk>> persisted = new ArrayList<>();
        Flux<AiConversationModelChunk> source = Flux.just(
                        new AiConversationModelChunk("cancelled", null, null, null))
                .concatWith(Flux.never());

        StepVerifier.withVirtualTime(
                        () -> AiConversationStreamBatcher.forwardWhileBatching(
                                source,
                                4096,
                                Duration.ofMillis(250),
                                batch -> persisted.add(batch),
                                scheduler),
                        () -> scheduler,
                        1)
                .expectNextMatches(chunk -> chunk.text().equals("cancelled"))
                .thenCancel()
                .verify();

        assertThat(persisted).singleElement()
                .satisfies(batch -> assertThat(batch)
                        .singleElement()
                        .extracting(AiConversationModelChunk::text)
                        .isEqualTo("cancelled"));
        scheduler.advanceTimeBy(Duration.ofSeconds(1));
        assertThat(persisted).hasSize(1);
    }
}
