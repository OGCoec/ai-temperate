package com.example.temperate.service.admin.aimodel.dto;

import java.util.List;

/**
 * 返回由 PageHelper 统计的 AI 模型页码分页结果及上下页状态。
 */
public record AdminAiModelPageResult(
        List<AdminAiModelResult> models,
        int pageNum,
        int pageSize,
        long total,
        int pages,
        boolean hasPrevious,
        boolean hasNext) {

    public AdminAiModelPageResult {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
