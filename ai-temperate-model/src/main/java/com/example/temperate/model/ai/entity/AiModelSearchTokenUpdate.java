package com.example.temperate.model.ai.entity;

/**
 * 承载 AI 模型搜索词元批量回填的一行固定参数。
 *
 * <p>该记录只在 Mapper 批量更新边界传递模型 ID 与两个 JSONB 数组，不携带模型展示文本，
 * 也不负责版本递增或缓存刷新。</p>
 */
public record AiModelSearchTokenUpdate(
        long id,
        String modelNameTokensJson,
        String descriptionTokensJson) {
}
