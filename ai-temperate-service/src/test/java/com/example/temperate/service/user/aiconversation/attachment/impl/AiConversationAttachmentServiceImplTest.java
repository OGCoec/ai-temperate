package com.example.temperate.service.user.aiconversation.attachment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachment;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentCategory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentFinalization;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectKeyFactory;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentObjectStorage;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentState;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationAttachmentUploadReference;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationGeneratedMedia;
import com.example.temperate.service.user.aiconversation.attachment.AiConversationPreuploadFile;
import com.example.temperate.service.user.aiconversation.attachment.config.AiConversationAttachmentProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 验证会话附件预上传、HEAD 一致性和正式落盘失败占位遵守有界 OSS 访问契约。
 */
final class AiConversationAttachmentServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-30T12:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void createsPresignedPutForArbitraryMimeTypeUnderOwnedTemporaryPrefix() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        when(storage.generatePresignedPut(anyString(), anyString(), anyLong()))
                .thenReturn(new AiConversationAttachmentObjectStorage.PresignedPut(
                        "https://oss.example.test/signed",
                        "PUT",
                        Map.of("Content-Type", "application/x-custom"),
                        CLOCK.instant().plusSeconds(600)));
        AiConversationAttachmentServiceImpl service = service(storage);

        var batch = service.createPreuploads(
                7L,
                "AAAAAAAAAAE",
                List.of(new AiConversationPreuploadFile(
                        "folder/custom.abc",
                        "application/x-custom",
                        12L)));

        assertThat(batch.uploadSessionId()).hasSize(22);
        assertThat(batch.files()).singleElement().satisfies(file -> {
            assertThat(file.attachmentId()).hasSize(38);
            assertThat(file.fileName()).isEqualTo("custom.abc");
            assertThat(file.sizeBytes()).isEqualTo("12");
            assertThat(file.uploadHeaders()).containsEntry(
                    "Content-Type", "application/x-custom");
        });
        verify(storage).generatePresignedPut(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(12L));
    }

    @Test
    void rejectsHeadMetadataThatDoesNotMatchTheSignedDeclaration() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        when(storage.headObject(anyString()))
                .thenReturn(new AiConversationAttachmentObjectStorage.ObjectMetadata(
                        13L,
                        "application/pdf"));
        AiConversationAttachmentServiceImpl service = service(storage);

        assertThatThrownBy(() -> service.validateTemporaryInputs(
                "AAAAAAAAAAE",
                List.of(new AiConversationAttachmentUploadReference(
                        "AAAAAAAAAAAAAAAAAAAAAQ",
                        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKL",
                        "manual.pdf",
                        "application/pdf",
                        12L))))
                .isInstanceOf(AiConversationException.class)
                .hasMessageContaining("实际大小");
    }

    @Test
    void retriesGeneratedMediaStorageAndPersistsFailurePlaceholder() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        when(storage.putPublic(anyString(), any(byte[].class), anyString()))
                .thenThrow(new AiConversationAttachmentObjectStorage.StorageException(
                        "test failure",
                        null));
        AiConversationAttachmentServiceImpl service = service(storage);

        var result = service.finalizeAttachments(
                "AAAAAAAAAAE",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                "AAAAAAAAAAI",
                List.of(),
                List.of(new AiConversationGeneratedMedia(
                        "generated.png",
                        "image/png",
                        new byte[] {1, 2, 3})));

        assertThat(result.partialFailure()).isTrue();
        assertThat(result.responseAttachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.state())
                    .isEqualTo(AiConversationAttachmentState.STORAGE_FAILED);
            assertThat(attachment.failureCode())
                    .isEqualTo(AiConversationAttachment.STORAGE_FAILURE_CODE);
            assertThat(attachment.category())
                    .isEqualTo(AiConversationAttachmentCategory.IMAGE);
        });
        verify(storage, times(3))
                .putPublic(anyString(), any(byte[].class), anyString());
    }

    @Test
    void persistsAtMostTenGeneratedImagesWithoutReusingInputLimit() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        when(storage.putPublic(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://public-oss.example.test/"
                        + invocation.getArgument(0, String.class));
        AiConversationAttachmentServiceImpl service = service(storage);
        List<AiConversationGeneratedMedia> generated = IntStream.range(0, 11)
                .mapToObj(index -> new AiConversationGeneratedMedia(
                        "generated-" + (index + 1) + ".webp",
                        "image/webp",
                        new byte[] {(byte) index}))
                .toList();

        var result = service.finalizeAttachments(
                "AAAAAAAAAAE",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                "AAAAAAAAAAI",
                List.of(),
                generated);

        assertThat(result.responseAttachments()).hasSize(11);
        assertThat(result.responseAttachments().subList(0, 10))
                .allMatch(attachment -> attachment.state()
                        == AiConversationAttachmentState.AVAILABLE);
        assertThat(result.responseAttachments().get(10).state())
                .isEqualTo(AiConversationAttachmentState.STORAGE_FAILED);
        assertThat(result.createdObjectKeys()).hasSize(10);
        verify(storage, times(10))
                .putPublic(anyString(), any(byte[].class), anyString());
    }

    @Test
    void retainsSuccessfulGeneratedObjectWhenAnotherUploadFails() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        when(storage.putPublic(anyString(), any(byte[].class), anyString()))
                .thenReturn("https://public-oss.example.test/generated-1.webp")
                .thenThrow(new AiConversationAttachmentObjectStorage.StorageException(
                        "second output failed",
                        null));
        AiConversationAttachmentServiceImpl service = service(storage);

        var result = service.finalizeAttachments(
                "AAAAAAAAAAE",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                "AAAAAAAAAAI",
                List.of(),
                List.of(
                        new AiConversationGeneratedMedia(
                                "generated-1.webp",
                                "image/webp",
                                new byte[] {1}),
                        new AiConversationGeneratedMedia(
                                "generated-2.webp",
                                "image/webp",
                                new byte[] {2})));

        assertThat(result.partialFailure()).isTrue();
        assertThat(result.responseAttachments())
                .extracting(AiConversationAttachment::state)
                .containsExactly(
                        AiConversationAttachmentState.AVAILABLE,
                        AiConversationAttachmentState.STORAGE_FAILED);
        assertThat(result.createdObjectKeys()).singleElement()
                .asString()
                .contains("generated-1.webp");
        verify(storage, times(4))
                .putPublic(anyString(), any(byte[].class), anyString());
    }

    @Test
    void compensationDeletesEveryUnreferencedCreatedObject() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        AiConversationAttachmentServiceImpl service = service(storage);

        service.compensateCreatedObjects(List.of("object-a", "object-b"));

        verify(storage).deleteObject("object-a");
        verify(storage).deleteObject("object-b");
    }

    @Test
    void totalByteLimitRejectsOversizedSlotButAllowsLaterSmallImage() {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        when(storage.putPublic(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> "https://public-oss.example.test/"
                        + invocation.getArgument(0, String.class));
        AiConversationAttachmentServiceImpl service = service(storage, 4L, 5L);

        var result = service.finalizeAttachments(
                "AAAAAAAAAAE",
                "AAAAAAAAAAAAAAAAAAAAAQ",
                "AAAAAAAAAAI",
                List.of(),
                List.of(
                        new AiConversationGeneratedMedia(
                                "generated-1.webp", "image/webp", new byte[4]),
                        new AiConversationGeneratedMedia(
                                "generated-2.webp", "image/webp", new byte[2]),
                        new AiConversationGeneratedMedia(
                                "generated-3.webp", "image/webp", new byte[1])));

        assertThat(result.responseAttachments())
                .extracting(AiConversationAttachment::state)
                .containsExactly(
                        AiConversationAttachmentState.AVAILABLE,
                        AiConversationAttachmentState.STORAGE_FAILED,
                        AiConversationAttachmentState.AVAILABLE);
        assertThat(result.createdObjectKeys()).hasSize(2);
        verify(storage, times(2))
                .putPublic(anyString(), any(byte[].class), anyString());
    }

    @Test
    void uploadsAtMostThreeGeneratedObjectsConcurrentlyAndKeepsOutputOrder()
            throws Exception {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        CountDownLatch firstWaveStarted = new CountDownLatch(3);
        CountDownLatch releaseUploads = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        when(storage.putPublic(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    firstWaveStarted.countDown();
                    try {
                        if (!releaseUploads.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test upload release timed out");
                        }
                        return "https://public-oss.example.test/"
                                + invocation.getArgument(0, String.class);
                    } finally {
                        active.decrementAndGet();
                    }
                });
        ExecutorService executor = Executors.newFixedThreadPool(3);
        AiConversationAttachmentServiceImpl service = service(
                storage, 104_857_600L, 209_715_200L, executor);
        AtomicReference<AiConversationAttachmentFinalization> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.startVirtualThread(() -> {
            try {
                result.set(service.finalizeAttachments(
                        "AAAAAAAAAAE",
                        "AAAAAAAAAAAAAAAAAAAAAQ",
                        "AAAAAAAAAAI",
                        List.of(),
                        IntStream.range(0, 5)
                                .mapToObj(index -> new AiConversationGeneratedMedia(
                                        "generated-" + (index + 1) + ".webp",
                                        "image/webp",
                                        new byte[] {(byte) index}))
                                .toList(),
                        Duration.ofSeconds(5)));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        try {
            assertThat(firstWaveStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(active.get()).isEqualTo(3);
            releaseUploads.countDown();
            caller.join(3_000L);

            assertThat(failure.get()).isNull();
            assertThat(maximumActive.get()).isEqualTo(3);
            assertThat(result.get().responseAttachments())
                    .extracting(AiConversationAttachment::fileName)
                    .containsExactly(
                            "generated-1.webp",
                            "generated-2.webp",
                            "generated-3.webp",
                            "generated-4.webp",
                            "generated-5.webp");
        } finally {
            releaseUploads.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void finalizationTimeoutDeletesObjectCreatedByLateUpload() throws Exception {
        AiConversationAttachmentObjectStorage storage =
                mock(AiConversationAttachmentObjectStorage.class);
        CountDownLatch uploadStarted = new CountDownLatch(1);
        CountDownLatch releaseUpload = new CountDownLatch(1);
        when(storage.putPublic(anyString(), any(byte[].class), anyString()))
                .thenAnswer(invocation -> {
                    uploadStarted.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseUpload.await(1, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            // 模拟底层 SDK 在同步请求返回前不能立即响应 Future.cancel 的边界。
                        }
                    }
                    return "https://public-oss.example.test/"
                            + invocation.getArgument(0, String.class);
                });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AiConversationAttachmentServiceImpl service = service(
                storage, 104_857_600L, 209_715_200L, executor);
        try {
            assertThatThrownBy(() -> service.finalizeAttachments(
                    "AAAAAAAAAAE",
                    "AAAAAAAAAAAAAAAAAAAAAQ",
                    "AAAAAAAAAAI",
                    List.of(),
                    List.of(new AiConversationGeneratedMedia(
                            "generated-1.webp",
                            "image/webp",
                            new byte[] {1})),
                    Duration.ofMillis(50)))
                    .isInstanceOf(AiConversationException.class)
                    .hasMessageContaining("超时");
            assertThat(uploadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            releaseUpload.countDown();

            verify(storage, org.mockito.Mockito.timeout(1000))
                    .deleteObject(org.mockito.ArgumentMatchers.contains("generated-1.webp"));
        } finally {
            releaseUpload.countDown();
            executor.shutdownNow();
        }
    }

    private static AiConversationAttachmentServiceImpl service(
            AiConversationAttachmentObjectStorage storage) {
        return service(storage, 104_857_600L, 209_715_200L);
    }

    private static AiConversationAttachmentServiceImpl service(
            AiConversationAttachmentObjectStorage storage,
            long maxFileBytes,
            long maxTotalBytes) {
        return service(storage, maxFileBytes, maxTotalBytes, Runnable::run);
    }

    private static AiConversationAttachmentServiceImpl service(
            AiConversationAttachmentObjectStorage storage,
            long maxFileBytes,
            long maxTotalBytes,
            Executor executor) {
        return new AiConversationAttachmentServiceImpl(
                storage,
                new AiConversationAttachmentObjectKeyFactory(),
                new AiConversationAttachmentProperties(
                        "conversation-test",
                        "us-west-1",
                        "https://oss.example.test",
                        "https://public-oss.example.test",
                        Duration.ofMinutes(10),
                        Duration.ofMinutes(10),
                        maxFileBytes,
                        8,
                        maxTotalBytes,
                        3,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(30),
                        3,
                        4096,
                        256,
                        8192,
                        256,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        2),
                CLOCK,
                executor);
    }
}
