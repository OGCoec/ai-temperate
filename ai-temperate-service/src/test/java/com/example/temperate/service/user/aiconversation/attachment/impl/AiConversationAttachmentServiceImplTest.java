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

    private static AiConversationAttachmentServiceImpl service(
            AiConversationAttachmentObjectStorage storage) {
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
                        104_857_600L,
                        8,
                        209_715_200L,
                        3,
                        3,
                        4096,
                        256,
                        8192,
                        256,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10),
                        2),
                CLOCK);
    }
}
