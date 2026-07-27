package com.example.temperate.service.admin.aimodel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import com.example.temperate.mapper.ai.AiModelCapabilityMapper;
import com.example.temperate.mapper.ai.AiModelIconMapper;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelCapability;
import com.example.temperate.model.ai.entity.AiModelIcon;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortDirection;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortPriority;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelBatchStatusResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelCreateCommand;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelDetailResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPageResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPatchCommand;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelResult;
import com.example.temperate.service.admin.aimodel.dto.AiModelPatchField;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.example.temperate.service.admin.aimodel.id.AiModelIdGenerator;
import com.example.temperate.service.admin.aimodel.text.AiModelTextTokenizer;
import com.example.temperate.service.admin.aimodel.transaction.AiModelAfterCommitExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证管理员 AI 模型 Service 的 Snowflake 写入、能力校验、禁用缓存跳过和批量单次刷新编排。
 */
@ExtendWith(MockitoExtension.class)
final class AdminAiModelServiceImplTest {

    private static final long MODEL_ID = 123L;

    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AiModelCapabilityMapper capabilityMapper;
    @Mock
    private AiModelIconMapper iconMapper;
    @Mock
    private AiModelIdGenerator idGenerator;
    @Mock
    private SnowflakeIdWorker snowflakeIdWorker;
    @Mock
    private AiModelCacheService cacheService;
    @Mock
    private AiModelAfterCommitExecutor afterCommitExecutor;
    @Mock
    private AiModelTextTokenizer textTokenizer;

    private PublicIdCodec publicIdCodec;
    private AdminAiModelServiceImpl service;

    @BeforeEach
    void setUp() {
        publicIdCodec = new PublicIdCodec();
        service = new AdminAiModelServiceImpl(
                modelMapper,
                capabilityMapper,
                iconMapper,
                idGenerator,
                snowflakeIdWorker,
                publicIdCodec,
                new ObjectMapper(),
                textTokenizer,
                cacheService,
                afterCommitExecutor);
        lenient().when(textTokenizer.tokenize(any(String.class)))
                .thenReturn(List.of("token"));
        lenient().when(textTokenizer.tokenize(isNull()))
                .thenReturn(List.of());
        AtomicLong capabilityId = new AtomicLong(1000L);
        lenient().when(snowflakeIdWorker.nextId())
                .thenAnswer(invocation -> capabilityId.incrementAndGet());
    }

    @Test
    void createsEnabledModelWithSnowflakeIdAndDefersOneCacheRefresh() {
        when(modelMapper.countByNormalizedModelName("gpt-5.5")).thenReturn(0);
        when(idGenerator.nextPositiveId()).thenReturn(MODEL_ID);
        when(modelMapper.insert(any(AiModel.class))).thenReturn(1);
        when(capabilityMapper.insertBatch(any())).thenReturn(5);
        when(modelMapper.findById(MODEL_ID)).thenReturn(model(MODEL_ID, true));

        service.create(command(
                true,
                List.of("CHAT_COMPLETIONS", "RESPONSES", "IMAGE", "VIDEO", "AUDIO")));

        ArgumentCaptor<AiModel> modelCaptor = ArgumentCaptor.forClass(AiModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        assertThat(modelCaptor.getValue().getId()).isEqualTo(MODEL_ID);
        assertThat(modelCaptor.getValue().getEnabled()).isTrue();
        assertThat(modelCaptor.getValue().getModelNameTokensJson())
                .isEqualTo("[\"token\"]");
        assertThat(modelCaptor.getValue().getDescriptionTokensJson())
                .isEqualTo("[\"token\"]");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiModelCapability>> capabilities =
                ArgumentCaptor.forClass(List.class);
        verify(capabilityMapper).insertBatch(capabilities.capture());
        assertThat(capabilities.getValue())
                .extracting(AiModelCapability::getId)
                .allMatch(id -> id != null && id > 0)
                .doesNotHaveDuplicates();
        ArgumentCaptor<Runnable> refreshCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(afterCommitExecutor).execute(refreshCaptor.capture());
        verifyNoInteractions(cacheService);
        refreshCaptor.getValue().run();
        verify(cacheService).refreshEnabledSnapshot();
    }

    @Test
    void createsDisabledModelWithoutRegisteringCacheWork() {
        when(modelMapper.countByNormalizedModelName("gpt-5.5")).thenReturn(0);
        when(idGenerator.nextPositiveId()).thenReturn(MODEL_ID);
        when(modelMapper.insert(any(AiModel.class))).thenReturn(1);
        when(capabilityMapper.insertBatch(any())).thenReturn(1);
        when(modelMapper.findById(MODEL_ID)).thenReturn(model(MODEL_ID, false));

        service.create(command(false, List.of("RESPONSES")));

        verify(afterCommitExecutor, never()).execute(any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void nonPositiveCapabilitySnowflakeIdStopsBeforeCapabilityBatchInsert() {
        when(modelMapper.countByNormalizedModelName("gpt-5.5")).thenReturn(0);
        when(idGenerator.nextPositiveId()).thenReturn(MODEL_ID);
        when(modelMapper.insert(any(AiModel.class))).thenReturn(1);
        when(snowflakeIdWorker.nextId()).thenReturn(0L);

        assertThatThrownBy(() -> service.create(command(
                false,
                List.of("RESPONSES"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-positive");

        verify(capabilityMapper, never()).insertBatch(any());
    }

    @Test
    void createsModelWithLockedLogicalIconReferenceAndReturnsPublicIdAndUrl() {
        long iconId = 77L;
        String iconPublicId = publicIdCodec.encode(iconId);
        AiModelIcon icon = new AiModelIcon();
        icon.setId(iconId);
        when(iconMapper.findByIdForShare(iconId)).thenReturn(icon);
        when(modelMapper.countByNormalizedModelName("gpt-5.5")).thenReturn(0);
        when(idGenerator.nextPositiveId()).thenReturn(MODEL_ID);
        when(modelMapper.insert(any(AiModel.class))).thenReturn(1);
        when(capabilityMapper.insertBatch(any())).thenReturn(1);
        AiModel inserted = model(MODEL_ID, false);
        inserted.setIconId(iconId);
        inserted.setIcon("https://cdn.example.test/openai.png");
        when(modelMapper.findById(MODEL_ID)).thenReturn(inserted);

        AdminAiModelResult result = service.create(new AdminAiModelCreateCommand(
                "GPT-5.5",
                "test model",
                iconPublicId,
                List.of("chat"),
                "OpenAI",
                BigDecimal.ONE,
                BigDecimal.TWO,
                false,
                List.of("RESPONSES")));

        ArgumentCaptor<AiModel> modelCaptor = ArgumentCaptor.forClass(AiModel.class);
        verify(modelMapper).insert(modelCaptor.capture());
        assertThat(modelCaptor.getValue().getIconId()).isEqualTo(iconId);
        assertThat(result.iconPublicId()).isEqualTo(iconPublicId);
        assertThat(result.icon()).isEqualTo("https://cdn.example.test/openai.png");
    }

    @Test
    void rejectsMissingIconBeforeWritingModel() {
        long iconId = 77L;
        when(iconMapper.findByIdForShare(iconId)).thenReturn(null);

        assertThatThrownBy(() -> service.create(new AdminAiModelCreateCommand(
                "GPT-5.5",
                "test model",
                publicIdCodec.encode(iconId),
                List.of("chat"),
                "OpenAI",
                BigDecimal.ONE,
                BigDecimal.TWO,
                false,
                List.of("RESPONSES"))))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_ICON_NOT_FOUND));

        verify(modelMapper, never()).insert(any());
    }

    @Test
    void rejectsDuplicateCapabilityBeforeDatabaseIo() {
        assertThatThrownBy(() -> service.create(
                command(false, List.of("RESPONSES", "responses"))))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_CAPABILITY_DUPLICATED));

        verifyNoInteractions(modelMapper, capabilityMapper, idGenerator, cacheService);
    }

    @Test
    void rejectsUnsupportedCapabilityBeforeDatabaseIo() {
        assertThatThrownBy(() -> service.create(
                command(false, List.of("IMAGE_GENERATION"))))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_CAPABILITY_INVALID));

        verifyNoInteractions(modelMapper, capabilityMapper, idGenerator, cacheService);
    }

    @Test
    void batchStatusUsesOneBatchUpdateAndRegistersOneRefresh() {
        long secondId = 456L;
        List<String> publicIds = List.of(
                publicIdCodec.encode(MODEL_ID),
                publicIdCodec.encode(secondId));
        when(modelMapper.findByIds(List.of(MODEL_ID, secondId)))
                .thenReturn(List.of(model(MODEL_ID, false), model(secondId, false)));
        when(modelMapper.updateEnabledBatch(List.of(MODEL_ID, secondId), true)).thenReturn(2);

        AdminAiModelBatchStatusResult result = service.setEnabledBatch(publicIds, true);

        assertThat(result.requestedCount()).isEqualTo(2);
        assertThat(result.updatedCount()).isEqualTo(2);
        verify(modelMapper).findByIds(List.of(MODEL_ID, secondId));
        verify(modelMapper).updateEnabledBatch(List.of(MODEL_ID, secondId), true);
        verify(afterCommitExecutor).execute(any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void singleStatusChangeRegistersOneRefresh() {
        when(modelMapper.findById(MODEL_ID))
                .thenReturn(model(MODEL_ID, true), model(MODEL_ID, false));
        when(modelMapper.updateEnabled(MODEL_ID, false)).thenReturn(1);
        when(capabilityMapper.findByAiModelId(MODEL_ID)).thenReturn(List.of());

        service.setEnabled(publicIdCodec.encode(MODEL_ID), false);

        verify(modelMapper).updateEnabled(MODEL_ID, false);
        verify(afterCommitExecutor).execute(any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void listsModelsWithPageHelperMetadataAndOneCapabilityBatch() {
        long secondId = 456L;
        Page<AiModel> page = new Page<>(1, 2, true);
        page.setTotal(3);
        page.add(model(MODEL_ID, true));
        page.add(model(secondId, false));
        when(modelMapper.findPage("gpt\\%\\_", true)).thenReturn(page);
        when(capabilityMapper.findByAiModelIds(List.of(MODEL_ID, secondId)))
                .thenReturn(List.of(
                        capability(MODEL_ID, AiModelCapabilityCode.RESPONSES),
                        capability(secondId, AiModelCapabilityCode.IMAGE)));

        AdminAiModelPageResult result = service.list(
                1,
                2,
                "  GPT%_  ",
                true,
                AiModelSortPriority.INPUT_FIRST,
                AiModelSortDirection.ASC);

        assertThat(result.models()).hasSize(2);
        assertThat(result.models().get(0).capabilities())
                .containsExactly(AiModelCapabilityCode.RESPONSES);
        assertThat(result.models().get(1).capabilities())
                .containsExactly(AiModelCapabilityCode.IMAGE);
        assertThat(result.pageNum()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.hasPrevious()).isFalse();
        assertThat(result.hasNext()).isTrue();
        verify(modelMapper).findPage("gpt\\%\\_", true);
        verify(capabilityMapper).findByAiModelIds(List.of(MODEL_ID, secondId));
        assertThat(PageHelper.getLocalPage()).isNull();
    }

    @Test
    void clearsPageHelperStateWhenModelQueryFails() {
        when(modelMapper.findPage(null, null))
                .thenThrow(new IllegalStateException("query failed"));

        assertThatThrownBy(() -> service.list(
                1,
                20,
                null,
                null,
                AiModelSortPriority.OUTPUT_FIRST,
                AiModelSortDirection.DESC))
                .isInstanceOf(IllegalStateException.class);

        assertThat(PageHelper.getLocalPage()).isNull();
        verifyNoInteractions(capabilityMapper);
    }

    @Test
    void exposesOnlyFourServerControlledOrderByClauses() {
        assertThat(AiModelSortPriority.INPUT_FIRST.orderBy(AiModelSortDirection.ASC))
                .isEqualTo("input_ratio ASC, output_ratio ASC, model_name ASC");
        assertThat(AiModelSortPriority.INPUT_FIRST.orderBy(AiModelSortDirection.DESC))
                .isEqualTo("input_ratio DESC, output_ratio DESC, model_name DESC");
        assertThat(AiModelSortPriority.OUTPUT_FIRST.orderBy(AiModelSortDirection.ASC))
                .isEqualTo("output_ratio ASC, input_ratio ASC, model_name ASC");
        assertThat(AiModelSortPriority.OUTPUT_FIRST.orderBy(AiModelSortDirection.DESC))
                .isEqualTo("output_ratio DESC, input_ratio DESC, model_name DESC");
    }

    @Test
    void returnsFlatDetailWithVersionAndAllAvailableCapabilities() {
        AiModel stored = model(MODEL_ID, true);
        stored.setRowVersion(7L);
        when(modelMapper.findById(MODEL_ID)).thenReturn(stored);
        when(capabilityMapper.findByAiModelId(MODEL_ID))
                .thenReturn(List.of(capability(
                        MODEL_ID,
                        AiModelCapabilityCode.RESPONSES)));

        AdminAiModelDetailResult result =
                service.detail(publicIdCodec.encode(MODEL_ID));

        assertThat(result.rowVersion()).isEqualTo(7L);
        assertThat(result.capabilities())
                .containsExactly(AiModelCapabilityCode.RESPONSES);
        assertThat(result.availableCapabilities())
                .containsExactly(AiModelCapabilityCode.values());
    }

    @Test
    void patchesFieldsAndCapabilitiesWithOneOptimisticUpdate() {
        AiModel before = model(MODEL_ID, true);
        before.setRowVersion(3L);
        AiModel after = model(MODEL_ID, true);
        after.setModelName("gpt-5.6");
        after.setDescription(null);
        after.setRowVersion(4L);
        when(modelMapper.findById(MODEL_ID)).thenReturn(before, after);
        when(modelMapper.updateEditable(any(AiModel.class), org.mockito.ArgumentMatchers.eq(3L)))
                .thenReturn(1);
        when(capabilityMapper.deleteByAiModelId(MODEL_ID)).thenReturn(1);
        when(capabilityMapper.insertBatch(any())).thenReturn(2);
        when(capabilityMapper.findByAiModelId(MODEL_ID))
                .thenReturn(List.of(
                        capability(MODEL_ID, AiModelCapabilityCode.RESPONSES),
                        capability(MODEL_ID, AiModelCapabilityCode.IMAGE)));

        AdminAiModelDetailResult result = service.patch(
                publicIdCodec.encode(MODEL_ID),
                3L,
                patchCommand());

        assertThat(result.rowVersion()).isEqualTo(4L);
        ArgumentCaptor<AiModel> updated = ArgumentCaptor.forClass(AiModel.class);
        verify(modelMapper).updateEditable(updated.capture(), org.mockito.ArgumentMatchers.eq(3L));
        assertThat(updated.getValue().getModelName()).isEqualTo("gpt-5.6");
        assertThat(updated.getValue().getDescription()).isNull();
        verify(capabilityMapper).deleteByAiModelId(MODEL_ID);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiModelCapability>> capabilities =
                ArgumentCaptor.forClass(List.class);
        verify(capabilityMapper).insertBatch(capabilities.capture());
        assertThat(capabilities.getValue())
                .extracting(AiModelCapability::getId)
                .allMatch(id -> id != null && id > 0)
                .doesNotHaveDuplicates();
        verify(afterCommitExecutor).execute(any());
    }

    @Test
    void patchCanClearExistingIconWithoutLookingUpAnotherResource() {
        AiModel before = model(MODEL_ID, false);
        before.setIconId(77L);
        before.setRowVersion(1L);
        AiModel after = model(MODEL_ID, false);
        after.setIconId(null);
        after.setRowVersion(2L);
        when(modelMapper.findById(MODEL_ID)).thenReturn(before, after);
        when(modelMapper.updateEditable(any(AiModel.class), org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(1);
        when(capabilityMapper.findByAiModelId(MODEL_ID)).thenReturn(List.of());

        service.patch(
                publicIdCodec.encode(MODEL_ID),
                1L,
                new AdminAiModelPatchCommand(
                        AiModelPatchField.absent(),
                        AiModelPatchField.absent(),
                        AiModelPatchField.of(null),
                        AiModelPatchField.absent(),
                        AiModelPatchField.absent(),
                        AiModelPatchField.absent(),
                        AiModelPatchField.absent(),
                        AiModelPatchField.absent()));

        ArgumentCaptor<AiModel> updated = ArgumentCaptor.forClass(AiModel.class);
        verify(modelMapper).updateEditable(updated.capture(), org.mockito.ArgumentMatchers.eq(1L));
        assertThat(updated.getValue().getIconId()).isNull();
        verifyNoInteractions(iconMapper);
    }

    @Test
    void rejectsStalePatchBeforeAnyWriteOrCacheRefresh() {
        AiModel current = model(MODEL_ID, true);
        current.setRowVersion(4L);
        when(modelMapper.findById(MODEL_ID)).thenReturn(current);

        assertThatThrownBy(() -> service.patch(
                publicIdCodec.encode(MODEL_ID),
                3L,
                patchCommand()))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_VERSION_CONFLICT));

        verify(modelMapper, never()).updateEditable(any(), org.mockito.ArgumentMatchers.anyLong());
        verify(capabilityMapper, never()).deleteByAiModelId(
                org.mockito.ArgumentMatchers.anyLong());
        verify(afterCommitExecutor, never()).execute(any());
    }

    @Test
    void disabledModelPatchDoesNotReplaceCapabilitiesOrRefreshCache() {
        AiModel before = model(MODEL_ID, false);
        before.setRowVersion(1L);
        AiModel after = model(MODEL_ID, false);
        after.setDescription("updated");
        after.setRowVersion(2L);
        when(modelMapper.findById(MODEL_ID)).thenReturn(before, after);
        when(modelMapper.updateEditable(any(AiModel.class), org.mockito.ArgumentMatchers.eq(1L)))
                .thenReturn(1);
        when(capabilityMapper.findByAiModelId(MODEL_ID)).thenReturn(List.of());

        service.patch(
                publicIdCodec.encode(MODEL_ID),
                1L,
                descriptionPatch("updated"));

        verify(capabilityMapper, never()).deleteByAiModelId(
                org.mockito.ArgumentMatchers.anyLong());
        verify(capabilityMapper, never()).insertBatch(any());
        verify(afterCommitExecutor, never()).execute(any());
        verifyNoInteractions(cacheService);
    }

    @Test
    void concurrentUpdateRaceReturnsVersionConflictWithoutCacheWork() {
        AiModel before = model(MODEL_ID, true);
        before.setRowVersion(2L);
        when(modelMapper.findById(MODEL_ID)).thenReturn(before);
        when(modelMapper.updateEditable(any(AiModel.class), org.mockito.ArgumentMatchers.eq(2L)))
                .thenReturn(0);

        assertThatThrownBy(() -> service.patch(
                publicIdCodec.encode(MODEL_ID),
                2L,
                descriptionPatch("updated")))
                .isInstanceOfSatisfying(AdminAiModelException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(AdminAiModelErrorCode.AI_MODEL_VERSION_CONFLICT));

        verify(afterCommitExecutor, never()).execute(any());
        verifyNoInteractions(cacheService);
    }

    private static AdminAiModelCreateCommand command(
            boolean enabled,
            List<String> capabilities) {
        return new AdminAiModelCreateCommand(
                " GPT-5.5 ",
                "test model",
                null,
                List.of("chat"),
                "OpenAI",
                BigDecimal.ONE,
                BigDecimal.TWO,
                enabled,
                capabilities);
    }

    private static AdminAiModelPatchCommand patchCommand() {
        return new AdminAiModelPatchCommand(
                AiModelPatchField.of(" GPT-5.6 "),
                AiModelPatchField.of(null),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.of(List.of("RESPONSES", "IMAGE")));
    }

    private static AdminAiModelPatchCommand descriptionPatch(String description) {
        return new AdminAiModelPatchCommand(
                AiModelPatchField.absent(),
                AiModelPatchField.of(description),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent(),
                AiModelPatchField.absent());
    }

    private static AiModel model(long id, boolean enabled) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setModelName("gpt-5.5");
        model.setDescription("test model");
        model.setTagsJson("[]");
        model.setModelNameTokensJson("[]");
        model.setDescriptionTokensJson("[]");
        model.setVendor("openai");
        model.setInputRatio(BigDecimal.ONE);
        model.setOutputRatio(BigDecimal.TWO);
        model.setEnabled(enabled);
        model.setRowVersion(1L);
        model.setCreatedAt(LocalDate.of(2026, 7, 26));
        model.setUpdatedAt(LocalDate.of(2026, 7, 26));
        return model;
    }

    private static AiModelCapability capability(
            long modelId,
            AiModelCapabilityCode code) {
        AiModelCapability capability = new AiModelCapability();
        capability.setAiModelId(modelId);
        capability.setCapabilityCode(code);
        return capability;
    }
}
