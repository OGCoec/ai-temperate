package com.example.temperate.service.admin.aimodel.domain;

import java.util.Objects;

/**
 * 定义管理员 AI 模型列表的两种倍率排序优先级，并只生成服务端固定的 PageHelper 排序表达式。
 */
public enum AiModelSortPriority {
    INPUT_FIRST("input_ratio", "output_ratio"),
    OUTPUT_FIRST("output_ratio", "input_ratio");

    private final String primaryColumn;
    private final String secondaryColumn;

    AiModelSortPriority(String primaryColumn, String secondaryColumn) {
        this.primaryColumn = primaryColumn;
        this.secondaryColumn = secondaryColumn;
    }

    /**
     * 根据白名单枚举生成统一方向的三字段排序，禁止拼接任何客户端原始字段或方向。
     */
    public String orderBy(AiModelSortDirection direction) {
        String keyword = Objects.requireNonNull(direction, "direction must not be null").name();
        return primaryColumn + " " + keyword
                + ", " + secondaryColumn + " " + keyword
                + ", model_name " + keyword;
    }
}
