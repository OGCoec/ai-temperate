package com.example.temperate.web.admin.aimodel;

import com.example.temperate.service.admin.aimodel.backfill.AiModelTokenBackfillBatchResult;
import com.example.temperate.service.admin.aimodel.backfill.AiModelTokenBackfillService;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 在显式维护开关开启时启动 AI 模型历史词元回填。
 *
 * <p>任务以五百条 keyset 批次同步推进，失败会阻止应用完成启动；日志只包含批次数量和进度 ID，
 * 不记录模型文本、词元或缓存内容。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "app.ai-model-token-backfill",
        name = "enabled",
        havingValue = "true")
public final class AiModelTokenBackfillRunner implements ApplicationRunner {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiModelTokenBackfillRunner.class);
    private static final int BATCH_SIZE = 500;

    private final AiModelTokenBackfillService backfillService;

    public AiModelTokenBackfillRunner(AiModelTokenBackfillService backfillService) {
        this.backfillService = Objects.requireNonNull(backfillService);
    }

    @Override
    public void run(ApplicationArguments args) {
        long afterId = 0L;
        long scanned = 0L;
        long updated = 0L;
        while (true) {
            AiModelTokenBackfillBatchResult batch =
                    backfillService.backfillAfter(afterId, BATCH_SIZE);
            scanned += batch.scanned();
            updated += batch.updated();
            if (!batch.hasMore()) {
                LOGGER.info(
                        "event=ai_model_token_backfill_completed scanned={} updated={}",
                        scanned,
                        updated);
                return;
            }
            if (batch.lastId() <= afterId) {
                throw new IllegalStateException(
                        "AI model token backfill keyset cursor did not advance.");
            }
            afterId = batch.lastId();
        }
    }
}
