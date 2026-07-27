package com.example.temperate.service.admin.aimodel.id;

/**
 * 定义 AI 模型内部正数主键的生成边界。
 */
public interface AiModelIdGenerator {

    long nextPositiveId();
}
