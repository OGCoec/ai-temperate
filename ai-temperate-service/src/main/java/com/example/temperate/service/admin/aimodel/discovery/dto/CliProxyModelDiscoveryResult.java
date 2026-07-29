package com.example.temperate.service.admin.aimodel.discovery.dto;

import java.time.Instant;
import java.util.List;

/**
 * 表示一次不持久化的 CLIProxyAPI 模型发现结果及其后端读取时间。
 */
public record CliProxyModelDiscoveryResult(
        String source,
        Instant fetchedAt,
        int total,
        List<CliProxyDiscoveredModel> models) {

    public CliProxyModelDiscoveryResult {
        models = List.copyOf(models);
    }
}
