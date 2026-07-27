package com.example.temperate.service.admin.aimodel.backfill;

/**
 * 描述一次 AI 模型搜索词元 keyset 批次的进度。
 *
 * <p>调用方只能依赖末尾 ID 和是否存在下一批继续推进，禁止使用 OFFSET 造成大表回填退化。</p>
 */
public record AiModelTokenBackfillBatchResult(
        long lastId,
        int scanned,
        int updated,
        boolean hasMore) {
}
