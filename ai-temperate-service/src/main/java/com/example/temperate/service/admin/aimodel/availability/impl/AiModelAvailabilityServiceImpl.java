package com.example.temperate.service.admin.aimodel.availability.impl;

import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.service.admin.aimodel.availability.AiModelAvailabilityService;
import java.util.Objects;
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
}
