package com.example.temperate.service.user.aimodel.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.AiModelCapabilityMapper;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelCapability;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.aimodel.search.AiModelSearchCriteria;
import com.example.temperate.service.aimodel.search.AiModelSearchService;
import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogErrorCode;
import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.impl.AiConversationImageProfileServiceImpl;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证普通用户模型目录在空关键词时读取快照，在搜索时使用已启用数据库分页。
 */
@ExtendWith(MockitoExtension.class)
final class UserAiModelCatalogServiceImplTest {

    @Mock
    private AiModelCacheService cacheService;
    @Mock
    private AiModelMapper modelMapper;
    @Mock
    private AiModelCapabilityMapper capabilityMapper;
    @Mock
    private AiModelSearchService searchService;

    private final PublicIdCodec publicIdCodec = new PublicIdCodec();

    @Test
    void pagesStableEnabledSnapshotWithoutDatabaseAccess() {
        when(cacheService.getOrLoadEnabledSnapshot()).thenReturn(snapshot(
                entry(11L, "model-a"),
                entry(12L, "model-b"),
                entry(13L, "model-c")));
        when(searchService.prepare(null)).thenReturn(emptyCriteria());
        UserAiModelCatalogServiceImpl service = service();

        var result = service.list(2, 2, null);

        assertThat(result.models()).singleElement()
                .satisfies(model -> {
                     assertThat(model.publicId()).isEqualTo(publicIdCodec.encode(13L));
                     assertThat(model.modelName()).isEqualTo("model-c");
                     assertThat(model.icon()).isEqualTo("https://example.test/model.svg");
                     assertThat(model.cachedInputRatio())
                            .isEqualByComparingTo("0.25000000");
                    assertThat(model.supportedReasoningEffortLevels())
                            .containsExactly(
                                    (short) 1,
                                    (short) 2,
                                    (short) 3,
                                    (short) 4,
                                    (short) 5);
                    assertThat(model.defaultReasoningEffortLevel())
                            .isEqualTo((short) 2);
                    assertThat(model.modelNameMatchedTokens()).isEmpty();
                    assertThat(model.descriptionMatchedTokens()).isEmpty();
                });
        assertThat(result.total()).isEqualTo(3);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.hasPrevious()).isTrue();
        assertThat(result.hasNext()).isFalse();
        verifyNoInteractions(modelMapper, capabilityMapper);
    }

    @Test
    void searchesEnabledModelsThroughGinCriteriaAndOneCapabilityBatch() {
        AiModelSearchCriteria criteria = new AiModelSearchCriteria(
                "mini",
                List.of("mini"),
                "[\"mini\"]",
                List.of("mini"),
                "[\"mini\"]");
        when(searchService.prepare(" Mini ")).thenReturn(criteria);
        Page<AiModel> page = new Page<>(1, 20, true);
        page.setTotal(1);
        AiModel model = databaseModel(41L, "gpt-5.4-mini");
        page.add(model);
        when(modelMapper.findPage(
                "[\"mini\"]",
                "[\"mini\"]",
                "mini",
                true)).thenReturn(page);
        when(capabilityMapper.findByAiModelIds(List.of(41L)))
                .thenReturn(List.of(capability(41L, AiModelCapabilityCode.RESPONSES)));
        when(searchService.matchedDescriptionTokens(
                "[\"gpt\",\"mini\"]",
                criteria)).thenReturn(List.of("mini"));
        when(searchService.matchedModelNameTokens(
                "[\"gpt\",\"5.4\",\"mini\"]",
                criteria)).thenReturn(List.of("mini"));
        UserAiModelCatalogServiceImpl service = service();

        var result = service.list(1, 20, " Mini ");

        assertThat(result.models()).singleElement().satisfies(item -> {
             assertThat(item.modelName()).isEqualTo("gpt-5.4-mini");
             assertThat(item.icon()).isEqualTo("https://example.test/model.svg");
             assertThat(item.modelNameMatchedTokens()).containsExactly("mini");
            assertThat(item.descriptionMatchedTokens()).containsExactly("mini");
            assertThat(item.contextWindowTokens()).isEqualTo(256_000L);
            assertThat(item.contextWindowK()).isEqualTo(256L);
            assertThat(item.maxOutputTokens()).isEqualTo(32_000L);
            assertThat(item.maxOutputK()).isEqualTo(32L);
            assertThat(item.capabilities()).containsExactly(AiModelCapabilityCode.RESPONSES);
        });
        assertThat(result.total()).isEqualTo(1);
        verify(modelMapper).findPage(
                "[\"mini\"]",
                "[\"mini\"]",
                "mini",
                true);
        verify(capabilityMapper).findByAiModelIds(List.of(41L));
        verifyNoInteractions(cacheService);
        assertThat(PageHelper.getLocalPage()).isNull();
    }

    @Test
    void returnsEnabledModelDetailByPublicId() {
        when(cacheService.getOrLoadEnabledSnapshot())
                .thenReturn(snapshot(entry(21L, "model-detail")));
        UserAiModelCatalogServiceImpl service = service();

        var result = service.detail(publicIdCodec.encode(21L));

         assertThat(result.modelName()).isEqualTo("model-detail");
         assertThat(result.icon()).isEqualTo("https://example.test/model.svg");
         assertThat(result.capabilities()).containsExactly(AiModelCapabilityCode.RESPONSES);
        assertThat(result.supportedReasoningEffortLevels())
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
        assertThat(result.defaultReasoningEffortLevel()).isEqualTo((short) 2);
    }

    @Test
    void exposesOnlyThreeImageGenerationLevelsForGptImageModels() {
        when(cacheService.getOrLoadEnabledSnapshot()).thenReturn(snapshot(
                entry(51L, "gpt-image-2",
                        List.of(AiModelCapabilityCode.IMAGE_GENERATION)),
                entry(52L, "gpt-image-1.5",
                        List.of(AiModelCapabilityCode.IMAGE_GENERATION))));
        UserAiModelCatalogServiceImpl service = service();

        var image2 = service.detail(publicIdCodec.encode(51L));
        var image15 = service.detail(publicIdCodec.encode(52L));

        assertThat(image2.supportedImageGenerationLevels())
                .containsExactly((short) 1, (short) 2, (short) 3);
        assertThat(image15.supportedImageGenerationLevels())
                .containsExactly((short) 1, (short) 2, (short) 3);
        assertThat(image2.supportedImageAspects())
                .containsExactly(AiConversationImageAspect.values());
    }

    @Test
    void exposesXaiReasoningAndImageLevelsFromVendorWithoutModelNameGuessing() {
        when(cacheService.getOrLoadEnabledSnapshot()).thenReturn(snapshot(
                entry(53L, "custom-admin-name", "xai",
                        List.of(AiModelCapabilityCode.IMAGE_GENERATION))));
        UserAiModelCatalogServiceImpl service = service();

        var result = service.detail(publicIdCodec.encode(53L));

        assertThat(result.supportedReasoningEffortLevels())
                .containsExactly((short) 1, (short) 2, (short) 3);
        assertThat(result.defaultReasoningEffortLevel()).isEqualTo((short) 2);
        assertThat(result.supportedImageGenerationLevels())
                .containsExactly((short) 1, (short) 3);
    }

    @Test
    void rejectsInvalidPaginationBeforeReadingCache() {
        UserAiModelCatalogServiceImpl service = service();

        assertThatThrownBy(() -> service.list(0, 20, null))
                .isInstanceOf(UserAiModelCatalogException.class)
                .extracting(exception -> ((UserAiModelCatalogException) exception).code())
                .isEqualTo(UserAiModelCatalogErrorCode.AI_MODEL_PAGE_INVALID);
    }

    @Test
    void hidesModelThatIsAbsentFromEnabledSnapshot() {
        when(cacheService.getOrLoadEnabledSnapshot()).thenReturn(snapshot());
        UserAiModelCatalogServiceImpl service = service();

        assertThatThrownBy(() -> service.detail(publicIdCodec.encode(31L)))
                .isInstanceOf(UserAiModelCatalogException.class)
                .extracting(exception -> ((UserAiModelCatalogException) exception).code())
                .isEqualTo(UserAiModelCatalogErrorCode.AI_MODEL_NOT_FOUND);
    }

    private static AiModelCacheSnapshot snapshot(AiModelCacheEntry... entries) {
        return new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                List.of(entries));
    }

    private static AiModelCacheEntry entry(long id, String name) {
        return entry(id, name, List.of(AiModelCapabilityCode.RESPONSES));
    }

    private static AiModelCacheEntry entry(
            long id,
            String name,
            List<AiModelCapabilityCode> capabilities) {
        return entry(id, name, "openai", capabilities);
    }

    private static AiModelCacheEntry entry(
            long id,
            String name,
            String vendor,
            List<AiModelCapabilityCode> capabilities) {
        return new AiModelCacheEntry(
                id,
                name,
                vendor,
                "完整模型描述",
                "https://example.test/model.svg",
                List.of("代码", "推理"),
                new BigDecimal("1.00000000"),
                new BigDecimal("0.25000000"),
                new BigDecimal("4.00000000"),
                256000L,
                32000L,
                capabilities);
    }

    private UserAiModelCatalogServiceImpl service() {
        return new UserAiModelCatalogServiceImpl(
                cacheService,
                modelMapper,
                capabilityMapper,
                searchService,
                publicIdCodec,
                new ObjectMapper(),
                new AiConversationImageProfileServiceImpl(),
                new AiConversationImageGenerationProperties(
                        true,
                        "/v1/images/generations",
                        "/v1/images/edits",
                        33_554_432,
                        384_000,
                        768,
                        70,
                        268_435_456L));
    }

    private static AiModelSearchCriteria emptyCriteria() {
        return new AiModelSearchCriteria(null, List.of(), null, List.of(), null);
    }

    private static AiModel databaseModel(long id, String name) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setModelName(name);
        model.setVendor("openai");
        model.setDescription("支持 GPT Mini 模型");
        model.setIcon("https://example.test/model.svg");
        model.setTagsJson("[\"代码\",\"推理\"]");
        model.setModelNameTokensJson("[\"gpt\",\"5.4\",\"mini\"]");
        model.setDescriptionTokensJson("[\"gpt\",\"mini\"]");
        model.setInputRatio(new BigDecimal("1.00000000"));
        model.setCachedInputRatio(new BigDecimal("0.25000000"));
        model.setOutputRatio(new BigDecimal("4.00000000"));
        model.setContextWindowTokens(256_000L);
        model.setMaxOutputTokens(32_000L);
        model.setEnabled(true);
        model.setCreatedAt(LocalDate.of(2026, 7, 31));
        model.setUpdatedAt(LocalDate.of(2026, 7, 31));
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
