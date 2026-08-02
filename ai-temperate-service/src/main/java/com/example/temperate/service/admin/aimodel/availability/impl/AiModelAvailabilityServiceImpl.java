package com.example.temperate.service.admin.aimodel.availability.impl;

import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.service.admin.aimodel.availability.AiModelAvailabilityService;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 直接查询 PostgreSQL 确认 AI 模型当前是否启用，避免缓存短暂旧值继续放行已禁用模型。
 */
@Service
public final class AiModelAvailabilityServiceImpl implements AiModelAvailabilityService {

    private final AiModelMapper modelMapper;

    public AiModelAvailabilityServiceImpl(AiModelMapper modelMapper) {
        this.modelMapper = Objects.requireNonNull(modelMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(long internalModelId) {
        if (internalModelId <= 0) {
            return false;
        }
        return Boolean.TRUE.equals(modelMapper.findEnabledById(internalModelId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> findEnabledIds(List<Long> internalModelIds) {
        if (internalModelIds == null || internalModelIds.isEmpty()) {
            return Set.of();
        }
        List<Long> uniqueIds = internalModelIds.stream()
                .peek(id -> {
                    if (id == null || id <= 0) {
                        throw new IllegalArgumentException(
                                "AI model IDs must be positive.");
                    }
                })
                .distinct()
                .toList();
        // 候选模型必须一次批量确认，防止缓存旧值放行已停用模型，也避免逐模型数据库 I/O。
        List<Long> enabledIds = modelMapper.findEnabledIds(uniqueIds);
        return enabledIds == null || enabledIds.isEmpty()
                ? Set.of()
                : Set.copyOf(enabledIds);
    }
}
