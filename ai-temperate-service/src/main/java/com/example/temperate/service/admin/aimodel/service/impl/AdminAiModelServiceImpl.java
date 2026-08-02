package com.example.temperate.service.admin.aimodel.service.impl;

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
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelErrorCode;
import com.example.temperate.service.admin.aimodel.exception.AdminAiModelException;
import com.example.temperate.service.admin.aimodel.id.AiModelIdGenerator;
import com.example.temperate.service.admin.aimodel.service.AdminAiModelService;
import com.example.temperate.service.admin.aimodel.transaction.AiModelAfterCommitExecutor;
import com.example.temperate.service.aimodel.search.AiModelSearchCriteria;
import com.example.temperate.service.aimodel.search.AiModelSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排管理员 AI 模型新增、查询、乐观锁字段编辑和启停，并在数据库提交后重建加密缓存快照。
 *
 * <p>模型与能力在同一 PostgreSQL 本地事务中写入；批量启停先一次性验证全部 ID，再使用一条批量
 * SQL 更新。模型主记录禁止物理删除，能力明细只允许在字段编辑事务内整组替换；列表搜索返回的
 * 名称与描述命中词分别对应各自词元索引，不把任一索引分支的命中状态混入另一个展示字段。</p>
 */
@Service
public final class AdminAiModelServiceImpl implements AdminAiModelService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_TAGS = 20;
    private static final long TOKENS_PER_K = 1000L;
    private static final long MAX_TOKEN_LIMIT = 2_147_483_647_000L;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final AiModelMapper modelMapper;
    private final AiModelCapabilityMapper capabilityMapper;
    private final AiModelIconMapper iconMapper;
    private final AiModelIdGenerator idGenerator;
    private final SnowflakeIdWorker snowflakeIdWorker;
    private final PublicIdCodec publicIdCodec;
    private final ObjectMapper objectMapper;
    private final AiModelSearchService searchService;
    private final AiModelCacheService cacheService;
    private final AiModelAfterCommitExecutor afterCommitExecutor;

    public AdminAiModelServiceImpl(
            AiModelMapper modelMapper,
            AiModelCapabilityMapper capabilityMapper,
            AiModelIconMapper iconMapper,
            AiModelIdGenerator idGenerator,
            SnowflakeIdWorker snowflakeIdWorker,
            PublicIdCodec publicIdCodec,
            ObjectMapper objectMapper,
            AiModelSearchService searchService,
            AiModelCacheService cacheService,
            AiModelAfterCommitExecutor afterCommitExecutor) {
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper);
        this.iconMapper = Objects.requireNonNull(iconMapper);
        this.idGenerator = Objects.requireNonNull(idGenerator);
        this.snowflakeIdWorker = Objects.requireNonNull(snowflakeIdWorker);
        this.publicIdCodec = Objects.requireNonNull(publicIdCodec);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.searchService = Objects.requireNonNull(searchService);
        this.cacheService = Objects.requireNonNull(cacheService);
        this.afterCommitExecutor = Objects.requireNonNull(afterCommitExecutor);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminAiModelPageResult list(
            int pageNum,
            int pageSize,
            String keyword,
            Boolean enabled,
            AiModelSortPriority sortPriority,
            AiModelSortDirection direction) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || sortPriority == null || direction == null) {
            throw invalidInput("AI model page request is invalid.");
        }

        AiModelSearchCriteria searchCriteria;
        try {
            searchCriteria = searchService.prepare(keyword);
        } catch (IllegalArgumentException exception) {
            throw invalidInput(exception.getMessage());
        }
        List<AiModel> pageModels;
        PageInfo<AiModel> pageInfo;
        Page<AiModel> page = PageHelper.startPage(pageNum, pageSize, true);
        try {
            // 排序表达式只来自枚举白名单；PageHelper 上下文仅允许拦截紧随其后的模型主查询。
            page.setOrderBy(sortPriority.orderBy(direction));
            pageInfo = PageInfo.of(modelMapper.findPage(
                    searchCriteria.modelNameTokensJson(),
                    searchCriteria.descriptionTokensJson(),
                    searchCriteria.vendorExact(),
                    enabled));
            pageModels = List.copyOf(pageInfo.getList());
        } finally {
            // Servlet 工作线程会被复用，必须主动清理 ThreadLocal，避免能力查询或下一请求继承分页状态。
            PageHelper.clearPage();
        }

        Map<Long, List<AiModelCapabilityCode>> capabilities = loadCapabilities(pageModels);
        List<AdminAiModelResult> results = pageModels.stream()
                .map(model -> toResult(
                        model,
                        capabilities.getOrDefault(model.getId(), List.of()),
                        searchService.matchedModelNameTokens(
                                model.getModelNameTokensJson(),
                                searchCriteria),
                        searchService.matchedDescriptionTokens(
                                model.getDescriptionTokensJson(),
                                searchCriteria)))
                .toList();
        return new AdminAiModelPageResult(
                results,
                pageInfo.getPageNum(),
                pageInfo.getPageSize(),
                pageInfo.getTotal(),
                pageInfo.getPages(),
                pageInfo.isHasPreviousPage(),
                pageInfo.isHasNextPage());
    }

    @Override
    @Transactional(readOnly = true)
    public AdminAiModelDetailResult detail(String publicId) {
        long id = decodePublicId(publicId);
        AiModel model = requireModel(id);
        List<AiModelCapability> capabilities = capabilityMapper.findByAiModelId(id);
        return toDetailResult(
                model,
                capabilities.stream().map(AiModelCapability::getCapabilityCode).toList());
    }

    @Override
    @Transactional
    public AdminAiModelDetailResult patch(
            String publicId,
            long expectedVersion,
            AdminAiModelPatchCommand command) {
        if (expectedVersion < 1 || command == null || !command.hasChanges()) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_PATCH_INVALID,
                    "AI model patch is empty or invalid.");
        }
        long id = decodePublicId(publicId);
        AiModel current = requireModel(id);
        requireVersion(current, expectedVersion);
        NormalizedPatch normalized = normalizePatch(current, command);
        AiModel merged = mergeModel(current, normalized);

        try {
            int updated = modelMapper.updateEditable(merged, expectedVersion);
            if (updated == 0) {
                throw versionConflict();
            }
            if (updated != 1) {
                throw new IllegalStateException(
                        "AI model patch affected an unexpected row count.");
            }
            if (normalized.capabilitiesPresent()) {
                // 能力集合必须与主表修改处于同一事务；任何删除或批量插入失败都会回滚乐观锁递增。
                capabilityMapper.deleteByAiModelId(id);
                List<AiModelCapability> capabilityRows = normalized.capabilities().stream()
                        .map(code -> newCapability(id, code))
                        .toList();
                if (capabilityMapper.insertBatch(capabilityRows) != capabilityRows.size()) {
                    throw new IllegalStateException(
                            "AI model capability replacement affected an unexpected row count.");
                }
            }
        } catch (DuplicateKeyException exception) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_NAME_CONFLICT,
                    "AI model name already exists.",
                    exception);
        }

        if (Boolean.TRUE.equals(current.getEnabled())) {
            afterCommitExecutor.execute(cacheService::refreshEnabledSnapshot);
        }
        AiModel result = requireModel(id);
        List<AiModelCapability> capabilities = capabilityMapper.findByAiModelId(id);
        return toDetailResult(
                result,
                capabilities.stream().map(AiModelCapability::getCapabilityCode).toList());
    }

    @Override
    @Transactional
    public AdminAiModelResult create(AdminAiModelCreateCommand command) {
        NormalizedCreate normalized = normalize(command);
        if (modelMapper.countByNormalizedModelName(normalized.modelName()) > 0) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_NAME_CONFLICT,
                    "AI model name already exists.");
        }

        long modelId = idGenerator.nextPositiveId();
        AiModel model = new AiModel();
        model.setId(modelId);
        model.setModelName(normalized.modelName());
        model.setDescription(normalized.description());
        model.setIconId(normalized.iconId());
        model.setTagsJson(writeTags(normalized.tags()));
        model.setModelNameTokensJson(
                searchService.modelNameTokensJson(normalized.modelName()));
        model.setDescriptionTokensJson(
                searchService.descriptionTokensJson(normalized.description()));
        model.setVendor(normalized.vendor());
        model.setInputRatio(normalized.inputRatio());
        model.setCachedInputRatio(normalized.cachedInputRatio());
        model.setOutputRatio(normalized.outputRatio());
        model.setContextWindowTokens(normalized.contextWindowTokens());
        model.setMaxOutputTokens(normalized.maxOutputTokens());
        model.setEnabled(normalized.enabled());

        try {
            if (modelMapper.insert(model) != 1) {
                throw new IllegalStateException("AI model insert affected an unexpected row count.");
            }
        } catch (DuplicateKeyException exception) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_NAME_CONFLICT,
                    "AI model name already exists.",
                    exception);
        }

        List<AiModelCapability> capabilityRows = normalized.capabilities().stream()
                .map(code -> newCapability(modelId, code))
                .toList();
        if (capabilityMapper.insertBatch(capabilityRows) != capabilityRows.size()) {
            throw new IllegalStateException("AI model capability insert affected an unexpected row count.");
        }
        if (normalized.enabled()) {
            afterCommitExecutor.execute(cacheService::refreshEnabledSnapshot);
        }
        AiModel inserted = requireModel(modelId);
        return toResult(inserted, normalized.capabilities());
    }

    @Override
    @Transactional
    public AdminAiModelResult setEnabled(String publicId, boolean enabled) {
        long id = decodePublicId(publicId);
        AiModel current = requireModel(id);
        if (enabled) {
            requireConfiguredTokenLimits(current);
        }
        int updated = modelMapper.updateEnabled(id, enabled);
        if (updated > 1) {
            throw new IllegalStateException("AI model status update affected an unexpected row count.");
        }
        if (updated == 1) {
            afterCommitExecutor.execute(cacheService::refreshEnabledSnapshot);
        }
        AiModel result = updated == 1 ? requireModel(id) : current;
        List<AiModelCapability> capabilities = capabilityMapper.findByAiModelId(id);
        return toResult(
                result,
                capabilities.stream().map(AiModelCapability::getCapabilityCode).toList());
    }

    @Override
    @Transactional
    public AdminAiModelBatchStatusResult setEnabledBatch(
            List<String> publicIds,
            boolean enabled) {
        if (publicIds == null || publicIds.isEmpty() || publicIds.size() > MAX_BATCH_SIZE) {
            throw invalidInput("AI model batch size is invalid.");
        }
        List<Long> ids = new ArrayList<>(publicIds.size());
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (String publicId : publicIds) {
            long id = decodePublicId(publicId);
            if (!uniqueIds.add(id)) {
                throw new AdminAiModelException(
                        AdminAiModelErrorCode.AI_MODEL_BATCH_ID_DUPLICATED,
                        "AI model batch contains duplicate IDs.");
            }
            ids.add(id);
        }

        // 先用一次批量读取确认全部逻辑关联目标存在，避免 UPDATE 静默忽略不存在的资源。
        List<AiModel> existing = modelMapper.findByIds(ids);
        if (existing.size() != ids.size()
                || !existing.stream().map(AiModel::getId).collect(java.util.stream.Collectors.toSet())
                        .equals(uniqueIds)) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_NOT_FOUND,
                    "One or more AI models do not exist.");
        }
        if (enabled) {
            // 整批必须先验证完成再执行单条 UPDATE，避免部分模型启用后才发现容量配置缺失。
            existing.forEach(AdminAiModelServiceImpl::requireConfiguredTokenLimits);
        }

        int updated = modelMapper.updateEnabledBatch(ids, enabled);
        if (updated < 0 || updated > ids.size()) {
            throw new IllegalStateException("AI model batch status update affected an unexpected row count.");
        }
        if (updated > 0) {
            // 整批事务只注册一次刷新，提交后不会按模型逐条访问 Redis。
            afterCommitExecutor.execute(cacheService::refreshEnabledSnapshot);
        }
        return new AdminAiModelBatchStatusResult(ids.size(), updated, enabled);
    }

    private NormalizedCreate normalize(AdminAiModelCreateCommand command) {
        if (command == null || command.enabled() == null) {
            throw invalidInput("AI model create input is incomplete.");
        }
        String modelName = requiredText(command.modelName(), 128, true);
        String vendor = requiredText(command.vendor(), 128, true);
        String description = optionalText(command.description(), 4000);
        Long iconId = requireIconId(command.iconPublicId());
        List<String> tags = normalizeTags(command.tags());
        BigDecimal inputRatio = validRatio(command.inputRatio());
        BigDecimal cachedInputRatio = validRatio(command.cachedInputRatio());
        BigDecimal outputRatio = validRatio(command.outputRatio());
        TokenLimits tokenLimits = requireValidInputTokenLimits(
                command.contextWindowTokens(),
                command.maxOutputTokens());
        List<AiModelCapabilityCode> capabilities = normalizeCapabilities(command.capabilities());
        return new NormalizedCreate(
                modelName,
                description,
                iconId,
                tags,
                vendor,
                inputRatio,
                cachedInputRatio,
                outputRatio,
                tokenLimits.contextWindowTokens(),
                tokenLimits.maxOutputTokens(),
                command.enabled(),
                capabilities);
    }

    private NormalizedPatch normalizePatch(
            AiModel current,
            AdminAiModelPatchCommand command) {
        String modelName = command.modelName().present()
                ? requiredText(command.modelName().value(), 128, true)
                : current.getModelName();
        String description = command.description().present()
                ? optionalText(command.description().value(), 4000)
                : current.getDescription();
        Long iconId = command.iconPublicId().present()
                ? requireIconId(command.iconPublicId().value())
                : current.getIconId();
        List<String> tags = command.tags().present()
                ? normalizeTags(command.tags().value())
                : readTags(current.getTagsJson());
        String vendor = command.vendor().present()
                ? requiredText(command.vendor().value(), 128, true)
                : current.getVendor();
        BigDecimal inputRatio = command.inputRatio().present()
                ? validRatio(command.inputRatio().value())
                : current.getInputRatio();
        BigDecimal cachedInputRatio = command.cachedInputRatio().present()
                ? validRatio(command.cachedInputRatio().value())
                : current.getCachedInputRatio();
        BigDecimal outputRatio = command.outputRatio().present()
                ? validRatio(command.outputRatio().value())
                : current.getOutputRatio();
        boolean tokenLimitsPresent = command.contextWindowTokens().present()
                || command.maxOutputTokens().present();
        Long contextWindowTokens = command.contextWindowTokens().present()
                ? command.contextWindowTokens().value()
                : current.getContextWindowTokens();
        Long maxOutputTokens = command.maxOutputTokens().present()
                ? command.maxOutputTokens().value()
                : current.getMaxOutputTokens();
        if (tokenLimitsPresent) {
            TokenLimits tokenLimits = requireValidInputTokenLimits(
                    contextWindowTokens,
                    maxOutputTokens);
            contextWindowTokens = tokenLimits.contextWindowTokens();
            maxOutputTokens = tokenLimits.maxOutputTokens();
        }
        List<AiModelCapabilityCode> capabilities = command.capabilities().present()
                ? normalizeCapabilities(command.capabilities().value())
                : List.of();
        return new NormalizedPatch(
                modelName,
                description,
                iconId,
                tags,
                vendor,
                inputRatio,
                cachedInputRatio,
                outputRatio,
                contextWindowTokens,
                maxOutputTokens,
                command.capabilities().present(),
                capabilities);
    }

    private AiModel mergeModel(AiModel current, NormalizedPatch normalized) {
        AiModel merged = new AiModel();
        merged.setId(current.getId());
        merged.setModelName(normalized.modelName());
        merged.setDescription(normalized.description());
        merged.setIconId(normalized.iconId());
        merged.setTagsJson(writeTags(normalized.tags()));
        merged.setModelNameTokensJson(
                searchService.modelNameTokensJson(normalized.modelName()));
        merged.setDescriptionTokensJson(
                searchService.descriptionTokensJson(normalized.description()));
        merged.setVendor(normalized.vendor());
        merged.setInputRatio(normalized.inputRatio());
        merged.setCachedInputRatio(normalized.cachedInputRatio());
        merged.setOutputRatio(normalized.outputRatio());
        merged.setContextWindowTokens(normalized.contextWindowTokens());
        merged.setMaxOutputTokens(normalized.maxOutputTokens());
        merged.setEnabled(current.getEnabled());
        merged.setRowVersion(current.getRowVersion());
        return merged;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.size() > MAX_TAGS) {
            throw invalidInput("AI model tags are invalid.");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            normalized.add(requiredText(tag, 64, false));
        }
        return List.copyOf(normalized);
    }

    private List<AiModelCapabilityCode> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()
                || capabilities.size() > AiModelCapabilityCode.values().length) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_CAPABILITY_INVALID,
                    "AI model capabilities are invalid.");
        }
        EnumSet<AiModelCapabilityCode> normalized = EnumSet.noneOf(AiModelCapabilityCode.class);
        for (String capability : capabilities) {
            AiModelCapabilityCode code;
            try {
                code = AiModelCapabilityCode.fromExternalCode(capability);
            } catch (IllegalArgumentException exception) {
                throw new AdminAiModelException(
                        AdminAiModelErrorCode.AI_MODEL_CAPABILITY_INVALID,
                        "AI model capability is unsupported.",
                        exception);
            }
            if (!normalized.add(code)) {
                throw new AdminAiModelException(
                        AdminAiModelErrorCode.AI_MODEL_CAPABILITY_DUPLICATED,
                        "AI model capability is duplicated.");
            }
        }
        return List.copyOf(normalized);
    }

    private Map<Long, List<AiModelCapabilityCode>> loadCapabilities(List<AiModel> models) {
        if (models.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = models.stream().map(AiModel::getId).toList();
        Map<Long, List<AiModelCapabilityCode>> grouped = new LinkedHashMap<>();
        List<AiModelCapability> capabilityRows = capabilityMapper.findByAiModelIds(ids);
        for (AiModelCapability capability : capabilityRows) {
            grouped.computeIfAbsent(capability.getAiModelId(), ignored -> new ArrayList<>())
                    .add(capability.getCapabilityCode());
        }
        return grouped;
    }

    private AdminAiModelResult toResult(
            AiModel model,
            List<AiModelCapabilityCode> capabilities) {
        return toResult(model, capabilities, List.of(), List.of());
    }

    private AdminAiModelResult toResult(
            AiModel model,
            List<AiModelCapabilityCode> capabilities,
            List<String> modelNameMatchedTokens,
            List<String> descriptionMatchedTokens) {
        TokenLimitView tokenLimits = toTokenLimitView(model);
        return new AdminAiModelResult(
                publicIdCodec.encode(model.getId()),
                model.getModelName(),
                modelNameMatchedTokens,
                model.getDescription(),
                descriptionMatchedTokens,
                encodeNullableId(model.getIconId()),
                model.getIcon(),
                readTags(model.getTagsJson()),
                model.getVendor(),
                model.getInputRatio(),
                model.getCachedInputRatio(),
                model.getOutputRatio(),
                model.getContextWindowTokens(),
                tokenLimits.contextWindowK(),
                model.getMaxOutputTokens(),
                tokenLimits.maxOutputK(),
                Boolean.TRUE.equals(model.getEnabled()),
                capabilities,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }

    private AdminAiModelDetailResult toDetailResult(
            AiModel model,
            List<AiModelCapabilityCode> capabilities) {
        Long rowVersion = model.getRowVersion();
        if (rowVersion == null || rowVersion < 1) {
            throw new IllegalStateException("AI model row version is invalid.");
        }
        TokenLimitView tokenLimits = toTokenLimitView(model);
        return new AdminAiModelDetailResult(
                publicIdCodec.encode(model.getId()),
                model.getModelName(),
                model.getDescription(),
                encodeNullableId(model.getIconId()),
                model.getIcon(),
                readTags(model.getTagsJson()),
                model.getVendor(),
                model.getInputRatio(),
                model.getCachedInputRatio(),
                model.getOutputRatio(),
                model.getContextWindowTokens(),
                tokenLimits.contextWindowK(),
                model.getMaxOutputTokens(),
                tokenLimits.maxOutputK(),
                Boolean.TRUE.equals(model.getEnabled()),
                List.copyOf(capabilities),
                List.of(AiModelCapabilityCode.values()),
                rowVersion,
                model.getCreatedAt(),
                model.getUpdatedAt());
    }

    private AiModel requireModel(long id) {
        AiModel model = modelMapper.findById(id);
        if (model == null) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_NOT_FOUND,
                    "AI model does not exist.");
        }
        return model;
    }

    private long decodePublicId(String publicId) {
        try {
            return publicIdCodec.decode(publicId);
        } catch (IllegalArgumentException exception) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_PUBLIC_ID_INVALID,
                    "AI model public ID is invalid.",
                    exception);
        }
    }

    private Long requireIconId(String publicId) {
        if (publicId == null) {
            return null;
        }
        long iconId;
        try {
            iconId = publicIdCodec.decode(publicId);
        } catch (IllegalArgumentException exception) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_ICON_PUBLIC_ID_INVALID,
                    "AI model icon public ID is invalid.",
                    exception);
        }
        // 共享锁与图标删除的排他锁互斥，保证本事务写入逻辑关联前图标不会并发消失。
        AiModelIcon icon = iconMapper.findByIdForShare(iconId);
        if (icon == null) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_ICON_NOT_FOUND,
                    "AI model icon does not exist.");
        }
        return iconId;
    }

    private String encodeNullableId(Long id) {
        return id == null ? null : publicIdCodec.encode(id);
    }

    private String writeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI model tags serialization failed.", exception);
        }
    }

    private List<String> readTags(String tagsJson) {
        try {
            return tagsJson == null ? List.of() : List.copyOf(objectMapper.readValue(tagsJson, STRING_LIST));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new IllegalStateException("AI model tags JSON is invalid.", exception);
        }
    }

    private AiModelCapability newCapability(
            long modelId,
            AiModelCapabilityCode code) {
        AiModelCapability capability = new AiModelCapability();
        capability.setId(nextCapabilityId());
        capability.setAiModelId(modelId);
        capability.setCapabilityCode(code);
        return capability;
    }

    private long nextCapabilityId() {
        // 能力集合仍批量落库，但每一行必须先取得独立雪花主键，避免恢复数据库自增依赖。
        long id = snowflakeIdWorker.nextId();
        if (id <= 0) {
            throw new IllegalStateException(
                    "AI model capability ID generator returned a non-positive ID.");
        }
        return id;
    }

    private static String requiredText(String value, int maxLength, boolean lowercase) {
        if (value == null) {
            throw invalidInput("Required AI model text is missing.");
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw invalidInput("AI model text length is invalid.");
        }
        return lowercase ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private static String optionalText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw invalidInput("Optional AI model text length is invalid.");
        }
        return normalized;
    }

    private static BigDecimal validRatio(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > 8
                || value.precision() - value.scale() > 12) {
            throw invalidInput("AI model ratio is invalid.");
        }
        return value;
    }

    private static TokenLimits requireValidInputTokenLimits(
            Long contextWindowTokens,
            Long maxOutputTokens) {
        if (!validConfiguredTokenLimits(contextWindowTokens, maxOutputTokens)) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID,
                    "AI model token limits are invalid.");
        }
        return new TokenLimits(contextWindowTokens, maxOutputTokens);
    }

    private static void requireConfiguredTokenLimits(AiModel model) {
        Long contextWindowTokens = model.getContextWindowTokens();
        Long maxOutputTokens = model.getMaxOutputTokens();
        if (contextWindowTokens == null || maxOutputTokens == null) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_REQUIRED,
                    "AI model token limits must be configured before enabling.");
        }
        if (!validConfiguredTokenLimits(contextWindowTokens, maxOutputTokens)) {
            throw new AdminAiModelException(
                    AdminAiModelErrorCode.AI_MODEL_TOKEN_LIMIT_INVALID,
                    "AI model token limits are invalid.");
        }
    }

    private static TokenLimitView toTokenLimitView(AiModel model) {
        Long contextWindowTokens = model.getContextWindowTokens();
        Long maxOutputTokens = model.getMaxOutputTokens();
        if (contextWindowTokens == null && maxOutputTokens == null) {
            return new TokenLimitView(null, null);
        }
        if (!validConfiguredTokenLimits(contextWindowTokens, maxOutputTokens)) {
            throw new IllegalStateException("AI model token limits are invalid.");
        }
        return new TokenLimitView(
                Math.toIntExact(contextWindowTokens / TOKENS_PER_K),
                Math.toIntExact(maxOutputTokens / TOKENS_PER_K));
    }

    private static boolean validConfiguredTokenLimits(
            Long contextWindowTokens,
            Long maxOutputTokens) {
        return validTokenLimit(contextWindowTokens)
                && validTokenLimit(maxOutputTokens)
                && maxOutputTokens <= contextWindowTokens;
    }

    private static boolean validTokenLimit(Long value) {
        return value != null
                && value > 0
                && value <= MAX_TOKEN_LIMIT
                && value % TOKENS_PER_K == 0;
    }

    private static AdminAiModelException invalidInput(String message) {
        return new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_INPUT_INVALID,
                message);
    }

    private static void requireVersion(AiModel model, long expectedVersion) {
        Long currentVersion = model.getRowVersion();
        if (currentVersion == null || currentVersion < 1) {
            throw new IllegalStateException("AI model row version is invalid.");
        }
        if (currentVersion != expectedVersion) {
            throw versionConflict();
        }
    }

    private static AdminAiModelException versionConflict() {
        return new AdminAiModelException(
                AdminAiModelErrorCode.AI_MODEL_VERSION_CONFLICT,
                "AI model version has changed.");
    }

    /**
     * 保存跨字段校验和规范化后的新增数据，确保数据库写入与缓存快照使用同一组值。
     */
    private record NormalizedCreate(
            String modelName,
            String description,
            Long iconId,
            List<String> tags,
            String vendor,
            BigDecimal inputRatio,
            BigDecimal cachedInputRatio,
            BigDecimal outputRatio,
            Long contextWindowTokens,
            Long maxOutputTokens,
            boolean enabled,
            List<AiModelCapabilityCode> capabilities) {
    }

    /**
     * 保存 Merge Patch 与当前持久化值合并后的完整可编辑状态。
     *
     * <p>能力字段单独保留出现标记，只有客户端明确提交时才执行整组物理替换。</p>
     */
    private record NormalizedPatch(
            String modelName,
            String description,
            Long iconId,
            List<String> tags,
            String vendor,
            BigDecimal inputRatio,
            BigDecimal cachedInputRatio,
            BigDecimal outputRatio,
            Long contextWindowTokens,
            Long maxOutputTokens,
            boolean capabilitiesPresent,
            List<AiModelCapabilityCode> capabilities) {
    }

    /**
     * 保存通过 Service 权威校验的原始 Token 对，避免创建和 Patch 路径分别解释容量规则。
     */
    private record TokenLimits(Long contextWindowTokens, Long maxOutputTokens) {
    }

    /**
     * 保存管理员响应使用的精确 K 展示值；双空表示历史模型尚未配置。
     */
    private record TokenLimitView(Integer contextWindowK, Integer maxOutputK) {
    }
}
