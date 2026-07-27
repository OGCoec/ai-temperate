package com.example.temperate.service.admin.aimodel.icon.dto;

import java.util.List;

/**
 * 表示模型图标资源的有界分页结果和导航元数据。
 */
public record AiModelIconPageResult(
        List<AiModelIconResult> icons,
        int pageNum,
        int pageSize,
        long total,
        int pages,
        boolean hasPrevious,
        boolean hasNext) {

    public AiModelIconPageResult {
        icons = icons == null ? List.of() : List.copyOf(icons);
    }
}
