package com.example.temperate.service.admin.aimodel.discovery.dto;

import java.math.BigDecimal;

/**
 * 表示一个 CLIProxyAPI 模型及其可选的本地目录匹配和计费倍率快照。
 */
public record CliProxyDiscoveredModel(
        String modelId,
        String owner,
        Long createdEpochSeconds,
        CliProxyModelMatchStatus matchStatus,
        String localModelPublicId,
        String localVendor,
        BigDecimal inputRatio,
        BigDecimal cachedInputRatio,
        BigDecimal outputRatio,
        Boolean localEnabled) {
}
