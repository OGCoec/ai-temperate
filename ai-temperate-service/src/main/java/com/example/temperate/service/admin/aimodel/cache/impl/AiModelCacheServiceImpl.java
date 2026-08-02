package com.example.temperate.service.admin.aimodel.cache.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.mapper.ai.AiModelCapabilityMapper;
import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelCapability;
import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheEntry;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheSnapshot;
import com.example.temperate.service.admin.aimodel.cache.ProtectedAiModelCacheSnapshot;
import com.example.temperate.service.admin.aimodel.config.AiModelCacheProperties;
import com.example.temperate.service.admin.aimodel.security.AiModelCacheProtector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 读取并验证 AES-GCM 加密快照，或从 PostgreSQL 批量重建全部启用 AI 模型的 Redis 快照。
 *
 * <p>模型与能力分别通过一次有界 SQL 读取，随后只对固定聚合 Key 执行失效和写入；无启用模型时也
 * 写入空快照。超过硬性容量上限时拒绝写入，防止产生 Redis BigKey。</p>
 */
@Service
public final class AiModelCacheServiceImpl implements AiModelCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiModelCacheServiceImpl.class);
    private static final int TARGET_BYTES = 8 * 1024;
    private static final int WARNING_BYTES = 10 * 1024;
    private static final int ABSOLUTE_BYTES = 64 * 1024;
    private static final long TOKENS_PER_K = 1000L;
    private static final long MAX_TOKEN_LIMIT = 2_147_483_647_000L;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final AiModelMapper modelMapper;
    private final AiModelCapabilityMapper capabilityMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final AiModelCacheProtector protector;
    private final AiModelCacheProperties properties;
    private final ObjectMapper objectMapper;
    private final Counter refreshCounter;
    private final Counter warningCounter;
    private final Counter rejectedCounter;
    private final Counter corruptCounter;
    private final Counter readFailureCounter;
    private final Counter loadWriteFailureCounter;

    public AiModelCacheServiceImpl(
            AiModelMapper modelMapper,
            AiModelCapabilityMapper capabilityMapper,
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            AiModelCacheProtector protector,
            AiModelCacheProperties properties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.capabilityMapper = Objects.requireNonNull(capabilityMapper);
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.protector = Objects.requireNonNull(protector);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        MeterRegistry registry = Objects.requireNonNull(meterRegistry);
        this.refreshCounter = registry.counter("ai.model.cache.refresh");
        this.warningCounter = registry.counter("ai.model.cache.size.warning");
        this.rejectedCounter = registry.counter("ai.model.cache.size.rejected");
        this.corruptCounter = registry.counter("ai.model.cache.read.corrupt");
        this.readFailureCounter = registry.counter("ai.model.cache.read.failure");
        this.loadWriteFailureCounter = registry.counter("ai.model.cache.load.write.failure");
    }

    @Override
    public Optional<AiModelCacheSnapshot> findEnabledSnapshot() {
        String cacheKey = keyFactory.aiModelEnabledSnapshotKey();
        String envelope;
        try {
            envelope = redisTemplate.opsForValue().get(cacheKey);
        } catch (RuntimeException exception) {
            readFailureCounter.increment();
            LOGGER.warn("event=ai_model_cache_read_failed operation=get", exception);
            return Optional.empty();
        }
        if (envelope == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(protector.unprotect(cacheKey, envelope));
        } catch (IllegalArgumentException exception) {
            // 损坏、错版本或认证失败的密文不能进入模型选择流程；删除后由数据库回源并等待下次重建。
            try {
                redisTemplate.unlink(cacheKey);
            } catch (RuntimeException unlinkException) {
                readFailureCounter.increment();
                LOGGER.warn("event=ai_model_cache_read_failed operation=unlink", unlinkException);
            }
            corruptCounter.increment();
            LOGGER.warn("event=ai_model_cache_snapshot_rejected reason=invalid_envelope");
            return Optional.empty();
        }
    }

    @Override
    public AiModelCacheSnapshot getOrLoadEnabledSnapshot() {
        Optional<AiModelCacheSnapshot> cached = findEnabledSnapshot();
        if (cached.isPresent()) {
            return cached.get();
        }

        AiModelCacheSnapshot loaded = loadSnapshotFromDatabase();
        try {
            writeSnapshot(loaded);
        } catch (RuntimeException exception) {
            // 回源结果已经来自 PostgreSQL；Redis 回填失败不能把正常的只读模型目录降级为接口失败。
            loadWriteFailureCounter.increment();
            LOGGER.warn(
                    "event=ai_model_cache_load_write_failed cause={}",
                    exception.getClass().getSimpleName());
        }
        return loaded;
    }

    @Override
    @Transactional(readOnly = true)
    public void refreshEnabledSnapshot() {
        writeSnapshot(loadSnapshotFromDatabase());
    }

    private AiModelCacheSnapshot loadSnapshotFromDatabase() {
        List<AiModel> models = modelMapper.findEnabled(properties.maxModels() + 1);
        if (models.size() > properties.maxModels()) {
            rejectedCounter.increment();
            throw new IllegalStateException("Enabled AI model snapshot exceeds the configured model limit.");
        }
        if (models.isEmpty()) {
            return new AiModelCacheSnapshot(
                    AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                    List.of());
        }

        List<Long> modelIds = models.stream().map(AiModel::getId).toList();
        List<AiModelCapability> capabilityRows = capabilityMapper.findByAiModelIds(modelIds);
        Map<Long, List<AiModelCapabilityCode>> capabilities = groupCapabilities(capabilityRows);
        List<AiModelCacheEntry> entries = models.stream()
                .map(model -> toEntry(model, capabilities.getOrDefault(model.getId(), List.of())))
                .toList();
        return new AiModelCacheSnapshot(
                AiModelCacheSnapshot.CURRENT_SCHEMA_VERSION,
                entries);
    }

    private void writeSnapshot(AiModelCacheSnapshot snapshot) {
        String cacheKey = keyFactory.aiModelEnabledSnapshotKey();
        ProtectedAiModelCacheSnapshot protectedSnapshot = protector.protect(cacheKey, snapshot);
        int storedBytes = protectedSnapshot.envelope().getBytes(StandardCharsets.UTF_8).length;
        enforceSize(
                Math.max(protectedSnapshot.plaintextBytes(), storedBytes),
                snapshot.models().size());

        // 空集合也写入版本化密文，避免“当前确实无启用模型”被误判为缓存未命中而持续穿透数据库。
        redisTemplate.unlink(cacheKey);
        redisTemplate.opsForValue().set(
                cacheKey,
                protectedSnapshot.envelope(),
                randomizedTtl());
        refreshCounter.increment();
    }

    private AiModelCacheEntry toEntry(
            AiModel model,
            List<AiModelCapabilityCode> capabilities) {
        Long contextWindowTokens = model.getContextWindowTokens();
        Long maxOutputTokens = model.getMaxOutputTokens();
        if (!validConfiguredTokenLimits(contextWindowTokens, maxOutputTokens)) {
            // 启用快照是后续模型调用的权威配置，缺失或非规范容量必须 Fail Closed。
            throw new IllegalStateException(
                    "Enabled AI model token limits are invalid.");
        }
        return new AiModelCacheEntry(
                model.getId(),
                model.getModelName(),
                model.getVendor(),
                model.getDescription(),
                model.getIcon(),
                readTags(model.getTagsJson()),
                model.getInputRatio(),
                model.getCachedInputRatio(),
                model.getOutputRatio(),
                contextWindowTokens,
                maxOutputTokens,
                capabilities);
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

    private List<String> readTags(String tagsJson) {
        try {
            if (tagsJson == null || tagsJson.isBlank()) {
                return List.of();
            }
            return List.copyOf(objectMapper.readValue(tagsJson, STRING_LIST));
        } catch (JsonProcessingException | NullPointerException exception) {
            throw new IllegalStateException("AI model tags JSON is invalid.", exception);
        }
    }

    private void enforceSize(int plaintextBytes, int modelCount) {
        if (plaintextBytes > ABSOLUTE_BYTES) {
            rejectedCounter.increment();
            throw new IllegalStateException("AI model cache snapshot exceeds 64 KiB.");
        }
        if (plaintextBytes > WARNING_BYTES) {
            warningCounter.increment();
            LOGGER.warn(
                    "event=ai_model_cache_snapshot_large bytes={} models={} targetBytes={} warningBytes={}",
                    plaintextBytes,
                    modelCount,
                    TARGET_BYTES,
                    WARNING_BYTES);
        }
    }

    private Duration randomizedTtl() {
        long minimumMillis = properties.minimumTtl().toMillis();
        long maximumMillis = properties.maximumTtl().toMillis();
        if (minimumMillis == maximumMillis) {
            return Duration.ofMillis(minimumMillis);
        }
        return Duration.ofMillis(
                ThreadLocalRandom.current().nextLong(minimumMillis, maximumMillis + 1));
    }

    private static Map<Long, List<AiModelCapabilityCode>> groupCapabilities(
            List<AiModelCapability> rows) {
        Map<Long, List<AiModelCapabilityCode>> grouped = new LinkedHashMap<>();
        for (AiModelCapability row : rows) {
            grouped.computeIfAbsent(row.getAiModelId(), ignored -> new ArrayList<>())
                    .add(row.getCapabilityCode());
        }
        return grouped;
    }
}
