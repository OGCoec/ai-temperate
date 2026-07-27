package com.example.temperate.service.admin.aimodel.dto;

/**
 * 返回管理员批量启停请求的目标数量、实际变化数量和统一目标状态。
 */
public record AdminAiModelBatchStatusResult(
        int requestedCount,
        int updatedCount,
        boolean enabled) {
}
