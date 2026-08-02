package com.example.temperate.service.admin.aimodel.backfill.impl;

import com.example.temperate.mapper.ai.AiModelMapper;
import com.example.temperate.model.ai.entity.AiModel;
import com.example.temperate.model.ai.entity.AiModelSearchTokenUpdate;
import com.example.temperate.service.admin.aimodel.backfill.AiModelTokenBackfillBatchResult;
import com.example.temperate.service.admin.aimodel.backfill.AiModelTokenBackfillService;
import com.example.temperate.service.aimodel.search.AiModelSearchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以最多五百条的 keyset 批次回填 AI 模型名称和描述词元。
 *
 * <p>每批只执行一次查询和一次 {@code UPDATE ... FROM (VALUES ...)}，不逐条写数据库；
 * 回填不递增业务版本，也不触发 Redis 快照刷新。</p>
 */
@Service
public final class AiModelTokenBackfillServiceImpl implements AiModelTokenBackfillService {

    private static final int MAX_BATCH_SIZE = 500;

    private final AiModelMapper modelMapper;
    private final AiModelSearchService searchService;

    public AiModelTokenBackfillServiceImpl(
            AiModelMapper modelMapper,
            AiModelSearchService searchService) {
        this.modelMapper = Objects.requireNonNull(modelMapper);
        this.searchService = Objects.requireNonNull(searchService);
    }

    @Override
    @Transactional
    public AiModelTokenBackfillBatchResult backfillAfter(long afterId, int batchSize) {
        if (afterId < 0 || batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("AI model token backfill range is invalid.");
        }
        List<AiModel> models = modelMapper.findTokenBackfillPage(afterId, batchSize);
        if (models.isEmpty()) {
            return new AiModelTokenBackfillBatchResult(afterId, 0, 0, false);
        }

        List<AiModelSearchTokenUpdate> updates = new ArrayList<>(models.size());
        for (AiModel model : models) {
            updates.add(new AiModelSearchTokenUpdate(
                    model.getId(),
                    searchService.modelNameTokensJson(model.getModelName()),
                    searchService.descriptionTokensJson(model.getDescription())));
        }
        int updated = modelMapper.updateSearchTokensBatch(updates);
        if (updated != updates.size()) {
            throw new IllegalStateException(
                    "AI model token backfill affected an unexpected row count.");
        }
        return new AiModelTokenBackfillBatchResult(
                models.get(models.size() - 1).getId(),
                models.size(),
                updated,
                models.size() == batchSize);
    }

}
