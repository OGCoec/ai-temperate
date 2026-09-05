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
import com.example.temperate.service.user.aiconversation.config.AiConversationImageGenerationProperties;
import com.example.temperate.service.user.aiconversation.config.AiConversationVideoGenerationProperties;
import com.example.temperate.service.user.aiconversation.exception.AiConversationException;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageAspect;
import com.example.temperate.service.user.aiconversation.image.AiConversationImageProfileService;
import com.example.temperate.service.user.aiconversation.model.AiConversationReasoningEffort;
import com.example.temperate.service.user.aiconversation.model.AiModelProvider;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoAspectRatio;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoDurationRange;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoProfileService;
import com.example.temperate.service.user.aiconversation.video.AiConversationVideoResolution;
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
 * <p>空关键词只在最多五百条的稳定快照上切片；非空关键词采用名称与厂商优先匹配、描述全文降级兜底
 * 的两阶段分层检索策略，防止描述中的交叉引用造成假阳性误匹配。当前页模型一次批量加载能力，禁止逐模型
 * 访问数据库或把禁用模型暴露给普通用户。名称与描述高亮词分别由对应的词元索引计算，保证名称命中
 * 不会伪装成描述命中，反之亦然。</p>
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
    private final AiConversationVideoProfileService videoProfileService;
    private final AiConversationVideoGenerationProperties videoProperties;

    public UserAiModelCatalogServiceImpl(
            AiModelCacheService cacheService,
            AiModelMapper modelMapper,
            AiModelCapabilityMapper capabilityMapper,
            AiModelSearchService searchService,
            PublicIdCodec publicIdCodec,
            ObjectMapper objectMapper,
            AiConversationImageProfileService imageProfileService,
            AiConversationImageGenerationProperties imageProperties,
            AiConversationVideoProfileService videoProfileService,
            AiConversationVideoGenerationProperties videoProperties) {
        this.cacheService = Objects.requireNonNull(cacheService);
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper);
        this.searchService = Objects.requireNonNull(searchService);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.imageProfileService = Objects.requireNonNull(imageProfileService);
        this.imageProperties = Objects.requireNonNull(imageProperties);
        this.videoProfileService = Objects.requireNonNull(videoProfileService);
        this.videoProperties = Objects.requireNonNull(videoProperties);
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
        PageInfo<AiModel> pageInfo = null;
        List<AiModel> pageModels = List.of();
        boolean matchedByPrimary = false;

        // 第一阶段（主匹配）：优先根据模型名称横杠词元与厂商精确匹配，避免长文本描述中的交叉推荐词造成假阳性。
        if (criteria.modelNameTokensJson() != null || criteria.vendorExact() != null) {
            Page<AiModel> page = PageHelper.startPage(pageNum, pageSize, true);
            try {
                // 普通用户搜索只允许固定主键顺序，禁止把客户端输入拼接为排序表达式。
                page.setOrderBy("model.id ASC");
                pageInfo = PageInfo.of(modelMapper.findPage(
                        criteria.modelNameTokensJson(),
                        null,
                        criteria.vendorExact(),
                        true));
                pageModels = List.copyOf(pageInfo.getList());
                if (pageInfo.getTotal() > 0) {
                    matchedByPrimary = true;
                }
            } finally {
                // 每次数据库查询后立即清空 PageHelper ThreadLocal，防止污染后续查询。
                PageHelper.clearPage();
            }
        }

        // 第二阶段（兜底降级）：仅当第一阶段 0 命中且存在描述分词时，自动降级执行描述全文分词检索。
        if (!matchedByPrimary && criteria.descriptionTokensJson() != null) {
            Page<AiModel> page = PageHelper.startPage(pageNum, pageSize, true);
            try {
                // 普通用户搜索只允许固定主键顺序，禁止把客户端输入拼接为排序表达式。
                page.setOrderBy("model.id ASC");
                pageInfo = PageInfo.of(modelMapper.findPage(
                        null,
                        criteria.descriptionTokensJson(),
                        null,
                        true));
                pageModels = List.copyOf(pageInfo.getList());
            } finally {
                // 每次数据库查询后立即清空 PageHelper ThreadLocal，防止污染后续查询。
                PageHelper.clearPage();
            }
        }

        if (pageInfo == null || pageInfo.getTotal() == 0) {
            return new UserAiModelPageResult(
                    List.of(),
                    pageNum,
                    pageSize,
                    0,
                    0,
                    false,
                    false);
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
                model.contextWindowTokens(),
                toK(model.contextWindowTokens()),
                model.maxOutputTokens(),
                toK(model.maxOutputTokens()),
                model.capabilities(),
                reasoningLevels(model.vendor()),
                AiConversationReasoningEffort.defaultLevel(),
                imageLevels(model.vendor(), model.modelName(), model.capabilities()),
                imageAspects(model.vendor(), model.modelName(), model.capabilities()),
                videoModes(model.vendor(), model.modelName(), model.capabilities()),
                videoResolutions(model.vendor(), model.modelName(), model.capabilities()),
                videoAspectRatios(model.vendor(), model.modelName(), model.capabilities()),
                videoDuration(model.vendor(), model.modelName(), model.capabilities()));
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
                model.getContextWindowTokens(),
                toK(model.getContextWindowTokens()),
                model.getMaxOutputTokens(),
                toK(model.getMaxOutputTokens()),
                capabilities,
                reasoningLevels(model.getVendor()),
                AiConversationReasoningEffort.defaultLevel(),
                imageLevels(model.getVendor(), model.getModelName(), capabilities),
                imageAspects(model.getVendor(), model.getModelName(), capabilities),
                videoModes(model.getVendor(), model.getModelName(), capabilities),
                videoResolutions(model.getVendor(), model.getModelName(), capabilities),
                videoAspectRatios(model.getVendor(), model.getModelName(), capabilities),
                videoDuration(model.getVendor(), model.getModelName(), capabilities));
    }

    private List<Short> imageLevels(
            String vendor,
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        AiModelProvider provider = providerOrNull(vendor);
        return imageProperties.enabled()
                && provider != null
                && capabilities.contains(AiModelCapabilityCode.IMAGE_GENERATION)
                && imageProfileService.supports(provider, modelName)
                ? imageProfileService.supportedLevels(provider, modelName)
                : List.of();
    }

    private List<AiConversationImageAspect> imageAspects(
            String vendor,
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        AiModelProvider provider = providerOrNull(vendor);
        return imageProperties.enabled()
                && provider != null
                && capabilities.contains(AiModelCapabilityCode.IMAGE_GENERATION)
                && imageProfileService.supports(provider, modelName)
                ? imageProfileService.supportedAspects(provider, modelName)
                : List.of();
    }

    private List<AiConversationVideoMode> videoModes(
            String vendor,
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        AiModelProvider provider = providerOrNull(vendor);
        if (!videoProperties.enabled()
                || provider == null
                || !videoProfileService.supports(provider, modelName)) {
            return List.of();
        }
        return videoProfileService.supportedModes(provider, modelName).stream()
                .filter(mode -> switch (mode) {
                    case TEXT_TO_VIDEO, IMAGE_TO_VIDEO, REFERENCE_TO_VIDEO ->
                            capabilities.contains(AiModelCapabilityCode.VIDEO_GENERATION);
                    case VIDEO_EDIT ->
                            capabilities.contains(AiModelCapabilityCode.VIDEO_EDIT)
                                    && capabilities.contains(AiModelCapabilityCode.VIDEO_INPUT);
                    case VIDEO_EXTEND ->
                            capabilities.contains(AiModelCapabilityCode.VIDEO_EXTENSION)
                                    && capabilities.contains(AiModelCapabilityCode.VIDEO_INPUT);
                })
                .toList();
    }

    private List<AiConversationVideoResolution> videoResolutions(
            String vendor,
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        AiModelProvider provider = providerOrNull(vendor);
        List<AiConversationVideoMode> modes = videoModes(
                vendor, modelName, capabilities);
        if (provider == null || modes.isEmpty()) {
            return List.of();
        }
        return modes.stream()
                .flatMap(mode -> videoProfileService
                        .supportedResolutions(provider, modelName, mode)
                        .stream())
                .distinct()
                .toList();
    }

    private List<AiConversationVideoAspectRatio> videoAspectRatios(
            String vendor,
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        boolean supportsGeneration = videoModes(vendor, modelName, capabilities)
                .stream()
                .anyMatch(mode -> mode == AiConversationVideoMode.TEXT_TO_VIDEO
                        || mode == AiConversationVideoMode.IMAGE_TO_VIDEO
                        || mode == AiConversationVideoMode.REFERENCE_TO_VIDEO);
        return supportsGeneration
                ? List.of(AiConversationVideoAspectRatio.values())
                : List.of();
    }

    private AiConversationVideoDurationRange videoDuration(
            String vendor,
            String modelName,
            List<AiModelCapabilityCode> capabilities) {
        List<AiConversationVideoMode> modes = videoModes(
                vendor, modelName, capabilities);
        if (modes.stream().anyMatch(mode ->
                mode == AiConversationVideoMode.TEXT_TO_VIDEO
                        || mode == AiConversationVideoMode.IMAGE_TO_VIDEO
                        || mode == AiConversationVideoMode.REFERENCE_TO_VIDEO)) {
            return new AiConversationVideoDurationRange(1, 15);
        }
        return modes.contains(AiConversationVideoMode.VIDEO_EXTEND)
                ? new AiConversationVideoDurationRange(2, 10)
                : null;
    }

    private static List<Short> reasoningLevels(String vendor) {
        AiModelProvider provider = providerOrNull(vendor);
        return provider == null ? List.of() : provider.supportedReasoningLevels();
    }

    private static AiModelProvider providerOrNull(String vendor) {
        try {
            return AiModelProvider.fromVendor(vendor);
        } catch (AiConversationException unsupported) {
            // 目录仍可展示管理员配置的未实现供应商，但不给出可调用档位，真正调用继续返回受控错误。
            return null;
        }
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

    private static long toK(long tokens) {
        return Math.floorDiv(Math.addExact(tokens, 999L), 1_000L);
    }
}
