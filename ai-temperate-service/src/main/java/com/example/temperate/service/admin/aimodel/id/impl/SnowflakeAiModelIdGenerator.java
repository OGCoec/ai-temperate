package com.example.temperate.service.admin.aimodel.id.impl;

import com.example.temperate.common.id.snowflake.component.SnowflakeIdWorker;
import com.example.temperate.service.admin.aimodel.id.AiModelIdGenerator;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 复用项目共享 SnowflakeIdWorker 为 AI 模型生成正数 BIGINT 主键。
 *
 * <p>该实现不创建独立 Worker，因而与注册等业务共享同一节点序列和时钟回拨保护。</p>
 */
@Component
public final class SnowflakeAiModelIdGenerator implements AiModelIdGenerator {

    private final SnowflakeIdWorker idWorker;

    public SnowflakeAiModelIdGenerator(SnowflakeIdWorker idWorker) {
        this.idWorker = Objects.requireNonNull(idWorker);
    }

    @Override
    public long nextPositiveId() {
        long id = idWorker.nextId();
        if (id <= 0) {
            throw new IllegalStateException("AI model ID generator returned a non-positive ID.");
        }
        return id;
    }
}
