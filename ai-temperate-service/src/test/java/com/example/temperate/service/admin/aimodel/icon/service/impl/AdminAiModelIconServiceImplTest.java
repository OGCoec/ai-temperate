package com.example.temperate.service.admin.aimodel.icon.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.model.ai.entity.AiModelIcon;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconErrorCode;
import com.example.temperate.service.admin.aimodel.icon.AiModelIconException;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconPatchCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconRemoteCreateCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconUploadCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconPatchField;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageFormat;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageMetadata;
import com.example.temperate.service.admin.aimodel.icon.image.AiModelIconImageValidator;
import com.example.temperate.service.admin.aimodel.icon.persistence.AdminAiModelIconPersistenceService;
import com.example.temperate.service.admin.aimodel.icon.remote.AiModelIconRemoteImageValidator;
import com.example.temperate.service.admin.aimodel.icon.remote.ValidatedRemoteIcon;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconObjectKeyFactory;
import com.example.temperate.service.admin.aimodel.icon.storage.AiModelIconObjectStorage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证模型图标业务编排正确区分外链与 OSS，并在数据库失败时执行有限的对象补偿。
 */
@ExtendWith(MockitoExtension.class)
final class AdminAiModelIconServiceImplTest {

    @Mock
    private AiModelIconMapper iconMapper;
    @Mock
    private AdminAiModelIconPersistenceService persistenceService;
    @Mock
    private AiModelIconRemoteImageValidator remoteValidator;
    @Mock
    private AiModelIconImageValidator imageValidator;
    @Mock
    private AiModelIconObjectStorage objectStorage;
    @Mock
    private SnowflakeIdWorker snowflakeIdWorker;

    private AdminAiModelIconServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminAiModelIconServiceImpl(
                iconMapper,
                persistenceService,
                remoteValidator,
                imageValidator,
                new AiModelIconObjectKeyFactory("ai-temperate/models/icons/"),
                objectStorage,
                snowflakeIdWorker,
                new PublicIdCodec(),
                new SimpleMeterRegistry());
        lenient().when(snowflakeIdWorker.nextId()).thenReturn(19L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "webp", "gif", "ico", "avif", "svg"})
    void externalUrlCreationPersistsFinalValidatedUrlWithoutObjectKey(
            String extension) {
        String sourceUrl = "https://redirect.example.test/openai." + extension;
        String finalUrl = "https://cdn.example.test/openai." + extension;
        when(remoteValidator.validate(sourceUrl))
                .thenReturn(new ValidatedRemoteIcon(
                        finalUrl));
        when(persistenceService.create(any(AiModelIcon.class)))
                .thenAnswer(invocation -> stored(invocation.getArgument(0)));

        var result = service.createRemote(new AdminAiModelIconRemoteCreateCommand(
                " OpenAI ",
                sourceUrl,
                " ChatGPT "));

        ArgumentCaptor<AiModelIcon> inserted = ArgumentCaptor.forClass(AiModelIcon.class);
        verify(persistenceService).create(inserted.capture());
        assertThat(inserted.getValue().getId()).isEqualTo(19L);
        assertThat(inserted.getValue().getIconUrl())
                .isEqualTo(finalUrl);
        assertThat(inserted.getValue().getObjectKey()).isNull();
        assertThat(result.iconName()).isEqualTo("OpenAI");
        verifyNoInteractions(objectStorage);
    }

    @Test
    void nonPositiveSnowflakeIdFailsBeforeRemoteIconPersistence() {
        String sourceUrl = "https://redirect.example.test/openai.png";
        when(remoteValidator.validate(sourceUrl))
                .thenReturn(new ValidatedRemoteIcon(
                        "https://cdn.example.test/openai.png"));
        when(snowflakeIdWorker.nextId()).thenReturn(0L);

        assertThatThrownBy(() -> service.createRemote(
                new AdminAiModelIconRemoteCreateCommand(
                        "OpenAI",
                        sourceUrl,
                        "ChatGPT")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-positive");

        verifyNoInteractions(persistenceService, objectStorage);
    }

    @Test
    void localUploadUsesRealFormatAndCompensatesDatabaseFailure() {
        byte[] bytes = {1, 2, 3};
        byte[] validatedBytes = {9, 8, 7};
        when(imageValidator.validate(any(byte[].class), eq("image/png")))
                .thenReturn(new AiModelIconImageMetadata(
                        AiModelIconImageFormat.PNG,
                        32,
                        32,
                        1,
                        validatedBytes));
        when(objectStorage.putObject(
                eq("ai-temperate/models/icons/openai.png"),
                any(byte[].class),
                eq("image/png"),
                eq(true)))
                .thenReturn("https://oss.example.test/ai-temperate/models/icons/openai.png");
        when(persistenceService.create(any(AiModelIcon.class)))
                .thenThrow(new IllegalStateException("database failed"));

        assertThatThrownBy(() -> service.createUpload(new AdminAiModelIconUploadCommand(
                "OpenAI",
                "ChatGPT",
                bytes,
                "image/png")))
                .isInstanceOf(IllegalStateException.class);

        verify(objectStorage).deleteObject("ai-temperate/models/icons/openai.png");
        verify(objectStorage).putObject(
                eq("ai-temperate/models/icons/openai.png"),
                eq(validatedBytes),
                eq("image/png"),
                eq(true));
    }

    @Test
    void localUploadCompensatesNonPositiveSnowflakeIdBeforePersistence() {
        byte[] bytes = {1, 2, 3};
        when(imageValidator.validate(bytes, "image/png"))
                .thenReturn(new AiModelIconImageMetadata(
                        AiModelIconImageFormat.PNG,
                        32,
                        32,
                        1,
                        bytes));
        when(objectStorage.putObject(
                "ai-temperate/models/icons/openai.png",
                bytes,
                "image/png",
                true))
                .thenReturn("https://oss.example.test/ai-temperate/models/icons/openai.png");
        when(snowflakeIdWorker.nextId()).thenReturn(0L);

        assertThatThrownBy(() -> service.createUpload(
                new AdminAiModelIconUploadCommand(
                        "OpenAI",
                        "ChatGPT",
                        bytes,
                        "image/png")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-positive");

        verify(objectStorage).deleteObject("ai-temperate/models/icons/openai.png");
        verifyNoInteractions(persistenceService);
    }

    @Test
    void switchingFromOssToExternalUrlDeletesOldObjectAfterDatabaseUpdate() {
        String publicId = new PublicIdCodec().encode(19L);
        AiModelIcon current = stored(new AiModelIcon());
        current.setIconName("OpenAI");
        current.setIconUrl("https://oss.example.test/ai-temperate/models/icons/openai.png");
        current.setObjectKey("ai-temperate/models/icons/openai.png");
        current.setDescription("ChatGPT");
        AiModelIcon updated = stored(new AiModelIcon());
        updated.setIconName("OpenAI");
        updated.setIconUrl("https://cdn.example.test/openai.webp");
        updated.setDescription("ChatGPT");
        when(iconMapper.findById(19L)).thenReturn(current);
        when(remoteValidator.validate("https://source.example.test/openai"))
                .thenReturn(new ValidatedRemoteIcon("https://cdn.example.test/openai.webp"));
        when(persistenceService.update(
                19L,
                "OpenAI",
                "https://cdn.example.test/openai.webp",
                null,
                "ChatGPT"))
                .thenReturn(updated);

        service.patch(publicId, new AdminAiModelIconPatchCommand(
                AiModelIconPatchField.absent(),
                AiModelIconPatchField.absent(),
                AiModelIconPatchField.of("https://source.example.test/openai")));

        verify(objectStorage).deleteObject("ai-temperate/models/icons/openai.png");
    }

    @Test
    void samePathReplacementDoesNotDeleteCurrentObjectWhenDatabaseUpdateFails() {
        String publicId = new PublicIdCodec().encode(19L);
        AiModelIcon current = stored(new AiModelIcon());
        current.setIconName("OpenAI");
        current.setIconUrl("https://oss.example.test/ai-temperate/models/icons/openai.png");
        current.setObjectKey("ai-temperate/models/icons/openai.png");
        byte[] bytes = {1, 2, 3};
        when(iconMapper.findById(19L)).thenReturn(current);
        when(imageValidator.validate(any(byte[].class), eq("image/png")))
                .thenReturn(new AiModelIconImageMetadata(
                        AiModelIconImageFormat.PNG,
                        32,
                        32,
                        1,
                        bytes));
        when(objectStorage.putObject(
                eq("ai-temperate/models/icons/openai.png"),
                any(byte[].class),
                eq("image/png"),
                eq(false)))
                .thenReturn("https://oss.example.test/ai-temperate/models/icons/openai.png");
        when(persistenceService.update(
                19L,
                "OpenAI",
                "https://oss.example.test/ai-temperate/models/icons/openai.png",
                "ai-temperate/models/icons/openai.png",
                null))
                .thenThrow(new IllegalStateException("database failed"));

        assertThatThrownBy(() -> service.replaceFile(publicId, bytes, "image/png"))
                .isInstanceOf(IllegalStateException.class);

        verify(objectStorage, never())
                .deleteObject("ai-temperate/models/icons/openai.png");
    }

    @Test
    void replacingWithDifferentRealFormatWritesNewSuffixThenDeletesOldObject() {
        String publicId = new PublicIdCodec().encode(19L);
        AiModelIcon current = stored(new AiModelIcon());
        current.setIconName("OpenAI");
        current.setIconUrl("https://oss.example.test/ai-temperate/models/icons/openai.png");
        current.setObjectKey("ai-temperate/models/icons/openai.png");
        byte[] sourceBytes = "<svg/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] safeBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1 1\"/>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AiModelIcon updated = stored(new AiModelIcon());
        updated.setIconName("OpenAI");
        updated.setIconUrl("https://oss.example.test/ai-temperate/models/icons/openai.svg");
        updated.setObjectKey("ai-temperate/models/icons/openai.svg");
        when(iconMapper.findById(19L)).thenReturn(current);
        when(imageValidator.validate(any(byte[].class), eq("image/svg+xml")))
                .thenReturn(new AiModelIconImageMetadata(
                        AiModelIconImageFormat.SVG,
                        1,
                        1,
                        1,
                        safeBytes));
        when(objectStorage.putObject(
                eq("ai-temperate/models/icons/openai.svg"),
                eq(safeBytes),
                eq("image/svg+xml"),
                eq(true)))
                .thenReturn(updated.getIconUrl());
        when(persistenceService.update(
                19L,
                "OpenAI",
                updated.getIconUrl(),
                updated.getObjectKey(),
                null))
                .thenReturn(updated);

        service.replaceFile(publicId, sourceBytes, "image/svg+xml");

        verify(objectStorage).putObject(
                eq("ai-temperate/models/icons/openai.svg"),
                eq(safeBytes),
                eq("image/svg+xml"),
                eq(true));
        verify(objectStorage).deleteObject("ai-temperate/models/icons/openai.png");
    }

    @Test
    void rejectsFileLargerThanTwoMibBeforeImageOrOssAccess() {
        byte[] bytes = new byte[(2 * 1024 * 1024) + 1];

        assertThatThrownBy(() -> service.createUpload(new AdminAiModelIconUploadCommand(
                "OpenAI",
                null,
                bytes,
                "image/png")))
                .isInstanceOfSatisfying(AiModelIconException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                AiModelIconErrorCode.AI_MODEL_ICON_FILE_TOO_LARGE));

        verify(imageValidator, never()).validate(any(byte[].class), any());
        verify(objectStorage, never()).putObject(any(), any(), any(), eq(true));
    }

    @Test
    void deletingPersistedOssIconRemovesExactObjectAfterDatabaseDelete() {
        String publicId = new PublicIdCodec().encode(19L);
        AiModelIcon deleted = stored(new AiModelIcon());
        deleted.setIconName("OpenAI");
        deleted.setIconUrl("https://oss.example.test/ai-temperate/models/icons/openai.png");
        deleted.setObjectKey("ai-temperate/models/icons/openai.png");
        when(persistenceService.delete(19L)).thenReturn(deleted);

        service.delete(publicId);

        verify(objectStorage).deleteObject("ai-temperate/models/icons/openai.png");
    }

    private static AiModelIcon stored(AiModelIcon value) {
        value.setId(19L);
        value.setCreatedAt(LocalDate.of(2026, 7, 27));
        value.setUpdatedAt(LocalDate.of(2026, 7, 27));
        return value;
    }
}
