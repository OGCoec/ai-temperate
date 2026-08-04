package com.example.temperate.service.user.aimodel.service.impl;

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
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.service.user.aimodel.dto.UserAiModelPageResult;
import com.example.temperate.service.user.aimodel.dto.UserAiModelResult;
import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogErrorCode;
import com.example.temperate.service.user.aimodel.exception.UserAiModelCatalogException;
import com.example.temperate.service.user.aimodel.service.UserAiModelCatalogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 从已启用模型快照提供普通浏览和图片档位能力，并通过 PostgreSQL 词元索引提供普通用户搜索分页。
 *
 * <p>空关键词只在最多五百条的稳定快照上切片；非空关键词固定查询已启用模型，并一次批量加载
 * 当前页能力，禁止逐模型访问数据库或把禁用模型暴露给普通用户。名称与描述高亮词分别由对应的
 * 词元索引计算，保证名称命中不会伪装成描述命中，反之亦然。</p>
 */
@Service
public final class UserAiModelCatalogServiceImpl implements UserAiModelCatalogService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final AiModelCacheService cacheService;
    private final AiModelMapper modelMapper;
    private final AiModelCapabilityMapper capabilityMapper;
    private final AiModelSearchService searchService;
    private final PublicIdCodec publicIdCodec;
    private final ObjectMapper objectMapper;
    private final AiConversationImageProfileService imageProfileService;
    private final AiConversationImageGenerationProperties imageProperties;

    public UserAiModelCatalogServiceImpl(
            AiModelCacheService cacheService,
            AiModelMapper modelMapper,
            AiModelCapabilityMapper capabilityMapper,
            AiModelSearchService searchService,
            PublicIdCodec publicIdCodec,
            ObjectMapper objectMapper,
            AiConversationImageProfileService imageProfileService,
            AiConversationImageGenerationProperties imageProperties) {
        this.cacheService = Objects.requireNonNull(cacheService);
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper);
        this.searchService = Objects.requireNonNull(searchService);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.imageProfileService = Objects.requireNonNull(imageProfileService);
        this.imageProperties = Objects.requireNonNull(imageProperties);
    }

    @Override
    public UserAiModelPageResult list(int pageNum, int pageSize, String keyword) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new UserAiModelCatalogException(
                    UserAiModelCatalogErrorCode.AI_MODEL_PAGE_INVALID,
                    "AI model page request is invalid.");
        }

        AiModelSearchCriteria criteria;
        try {
            criteria = searchService.prepare(keyword);
        } catch (IllegalArgumentException exception) {
            throw new UserAiModelCatalogException(
                    UserAiModelCatalogErrorCode.AI_MODEL_PAGE_INVALID,
                    "AI model search keyword is invalid.",
                    exception);
        }
        return criteria.hasKeyword()
                ? searchEnabledModels(pageNum, pageSize, criteria)
                : listEnabledSnapshot(pageNum, pageSize);
    }

    private UserAiModelPageResult listEnabledSnapshot(int pageNum, int pageSize) {
        AiModelCacheSnapshot snapshot = cacheService.getOrLoadEnabledSnapshot();
        List<AiModelCacheEntry> entries = snapshot.models();
        long total = entries.size();
        long start = (long) (pageNum - 1) * pageSize;
        int fromIndex = start >= total ? entries.size() : Math.toIntExact(start);
        int toIndex = (int) Math.min(total, start + pageSize);
        List<UserAiModelResult> models = entries.subList(fromIndex, toIndex).stream()
                .map(this::toResult)
                .toList();
        int pages = total == 0 ? 0 : Math.toIntExact((total + pageSize - 1) / pageSize);
        return new UserAiModelPageResult(
                models,
                pageNum,
                pageSize,
                total,
                pages,
                pageNum > 1 && total > 0,
                start + pageSize < total);
    }

    private UserAiModelPageResult searchEnabledModels(
            int pageNum,
            int pageSize,
            AiModelSearchCriteria criteria) {
        PageInfo<AiModel> pageInfo;
        List<AiModel> pageModels;
        Page<AiModel> page = PageHelper.startPage(pageNum, pageSize, true);
        try {
            // 普通用户搜索只允许固定主键顺序，禁止把客户端输入拼接为排序表达式。
            page.setOrderBy("model.id ASC");
            pageInfo = PageInfo.of(modelMapper.findPage(
                    criteria.modelNameTokensJson(),
                    criteria.descriptionTokensJson(),
                    criteria.vendorExact(),
                    true));
            pageModels = List.copyOf(pageInfo.getList());
        } finally {
            // 搜索后的能力批量查询不能继承 PageHelper ThreadLocal，否则会被误分页。
            PageHelper.clearPage();
        }

        Map<Long, List<AiModelCapabilityCode>> capabilities = loadCapabilities(pageModels);
        List<UserAiModelResult> results = pageModels.stream()
                .map(model -> toResult(
                        model,
                        capabilities.getOrDefault(model.getId(), List.of()),
                        criteria))
                .toList();
        return new UserAiModelPageResult(
                results,
                pageInfo.getPageNum(),
                pageInfo.getPageSize(),
                pageInfo.getTotal(),
                pageInfo.getPages(),
                pageInfo.isHasPreviousPage(),
                pageInfo.isHasNextPage());
    }

    @Override
    public UserAiModelResult detail(String publicId) {
        long internalId;
        try {
            internalId = publicIdCodec.decode(publicId);
        } catch (IllegalArgumentException exception) {
            throw new UserAiModelCatalogException(
                    UserAiModelCatalogErrorCode.AI_MODEL_PUBLIC_ID_INVALID,
                    "AI model public ID is invalid.",
                    exception);
        }

        return cacheService.getOrLoadEnabledSnapshot().models().stream()
                .filter(model -> model.id() == internalId)
                .findFirst()
                .map(this::toResult)
                .orElseThrow(() -> new UserAiModelCatalogException(
                        UserAiModelCatalogErrorCode.AI_MODEL_NOT_FOUND,
                        "AI model does not exist or is not enabled."));
    }

    private UserAiModelResult toResult(AiModelCacheEntry model) {
        return new UserAiModelResult(
                publicIdCodec.encode(model.id()),
                model.modelName(),
                List.of(),
                model.vendor(),
                model.description(),
                List.of(),
                model.icon(),
                model.tags(),
                model.inputRatio(),
                model.cachedInputRatio(),
                model.outputRatio(),
                model.capabilities(),
                AiConversationReasoningEffort.supportedLevels(),
                AiConversationReasoningEffort.defaultLevel(),
                imageLevels(model.modelName(), model.capabilities()),
                imageAspects(model.modelName(), model.capabilities()));
    }

    private UserAiModelResult toResult(
            AiModel model,
            List<AiModelCapabilityCode> capabilities,
            AiModelSearchCriteria criteria) {
        return new UserAiModelResult(
                publicIdCodec.encode(model.getId()),
                model.getModelName(),
                searchService.matchedModelNameTokens(
                        model.getModelNameTokensJson(),
                        criteria),
                model.getVendor(),
                model.getDescription(),
                searchService.matchedDescriptionTokens(
                        model.getDescriptionTokensJson(),
                        criteria),
                model.getIcon(),
                readTags(model.getTagsJson()),
                model.getInputRatio(),
                model.getCachedInputRatio(),
                model.getOutputRatio(),
                capabilities,
                AiConversationReasoningEffort.supportedLevels(),
                AiConversationReasoningEffort.defaultLevel(),
                imageLevels(model.getModelName(), capabilities),
                imageAspects(model.getModelName(), capabilities));
    }

    private List<Short> imageLevels(
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        return imageProperties.enabled()
                && capabilities.contains(AiModelCapabilityCode.IMAGE_GENERATION)
                && imageProfileService.supports(modelName)
                ? imageProfileService.supportedLevels(modelName)
                : List.of();
    }

    private List<AiConversationImageAspect> imageAspects(
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        return imageProperties.enabled()
                && capabilities.contains(AiModelCapabilityCode.IMAGE_GENERATION)
                && imageProfileService.supports(modelName)
                ? imageProfileService.supportedAspects(modelName)
                : List.of();
    }

    private Map<Long, List<AiModelCapabilityCode>> loadCapabilities(List<AiModel> models) {
        if (models.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = models.stream().map(AiModel::getId).toList();
        Map<Long, List<AiModelCapabilityCode>> grouped = new LinkedHashMap<>();
        for (Long id : ids) {
            grouped.put(id, new ArrayList<>());
        }
        List<AiModelCapability> capabilityRows = capabilityMapper.findByAiModelIds(ids);
        for (AiModelCapability capability : capabilityRows) {
            grouped.computeIfAbsent(capability.getAiModelId(), ignored -> new ArrayList<>())
                    .add(capability.getCapabilityCode());
        }
        return grouped;
    }

    private List<String> readTags(String tagsJson) {
        try {
            return tagsJson == null
                    ? List.of()
                    : List.copyOf(objectMapper.readValue(tagsJson, STRING_LIST));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new IllegalStateException("AI model tags JSON is invalid.", exception);
        }
    }
}
