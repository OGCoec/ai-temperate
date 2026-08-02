package com.example.temperate.service.aimodel.search;

import java.util.List;

/**
 * 表示一次模型目录搜索经过统一归一化后的数据库条件和描述匹配词元。
 *
 * <p>空关键词必须同时关闭两个 JSONB 分支和厂商分支；非空 JSON 字符串只允许表示至少一个词元，
 * 防止 PostgreSQL 的 {@code @> '[]'} 把全部模型误判为命中。</p>
 */
public record AiModelSearchCriteria(
        String vendorExact,
        List<String> modelNameTokens,
        String modelNameTokensJson,
        List<String> descriptionTokens,
        String descriptionTokensJson) {

    public AiModelSearchCriteria {
        modelNameTokens = modelNameTokens == null
                ? List.of()
                : List.copyOf(modelNameTokens);
        descriptionTokens = descriptionTokens == null
                ? List.of()
                : List.copyOf(descriptionTokens);
        if (modelNameTokens.isEmpty() != (modelNameTokensJson == null)) {
            throw new IllegalArgumentException(
                    "Model name query JSON must exist exactly when name tokens exist.");
        }
        if (descriptionTokens.isEmpty() != (descriptionTokensJson == null)) {
            throw new IllegalArgumentException(
                    "Description query JSON must exist exactly when description tokens exist.");
        }
    }

    public boolean hasKeyword() {
        return vendorExact != null
                || !modelNameTokens.isEmpty()
                || !descriptionTokens.isEmpty();
    }
}
