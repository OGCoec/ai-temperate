package com.example.temperate.service.user.aimodel.dto;

import java.util.List;

/**
 * 返回普通用户已启用 AI 模型目录的页码分页结果。
 */
public record UserAiModelPageResult(
        List<UserAiModelResult> models,
        int pageNum,
        int pageSize,
        long total,
        int pages,
        boolean hasPrevious,
        boolean hasNext) {

    public UserAiModelPageResult {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
