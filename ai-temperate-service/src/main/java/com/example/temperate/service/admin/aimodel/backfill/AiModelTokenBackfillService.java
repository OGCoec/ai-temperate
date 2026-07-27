package com.example.temperate.service.admin.aimodel.backfill;

/**
 * 定义 AI 模型历史搜索词元的有界批量回填能力。
 *
 * <p>每次调用只处理一个 keyset 批次，不负责启动生命周期、日志或缓存刷新。</p>
 */
public interface AiModelTokenBackfillService {

    AiModelTokenBackfillBatchResult backfillAfter(long afterId, int batchSize);
}
