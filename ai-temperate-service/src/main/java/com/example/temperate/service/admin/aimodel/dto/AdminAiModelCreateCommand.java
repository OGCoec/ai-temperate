package com.example.temperate.service.admin.aimodel.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 承载管理员新增 AI 模型时必须一次提交的不可变字段、原始 Token 限制和显式启用状态。
 *
 * <p>K 单位只在 Web 边界换算；Service 始终接收原始 Token，避免其他调用入口绕过容量不变量。</p>
 */
public record AdminAiModelCreateCommand(
        String modelName,
        String description,
        String iconPublicId,
        List<String> tags,
        String vendor,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        Long contextWindowTokens,
        Long maxOutputTokens,
        Boolean enabled,
        List<String> capabilities) {

    public AdminAiModelCreateCommand {
        // 保留空元素交给业务校验转换为受控错误，同时阻止调用方在构造后修改集合内容。
        tags = tags == null ? null : Collections.unmodifiableList(new ArrayList<>(tags));
        capabilities = capabilities == null
                ? null
                : Collections.unmodifiableList(new ArrayList<>(capabilities));
    }
}
