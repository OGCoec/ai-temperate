package com.example.temperate.service.admin.aimodel.domain;

/**
 * 限定管理员 AI 模型列表允许使用的整体排序方向，防止客户端把任意 SQL 片段传入 PageHelper。
 */
public enum AiModelSortDirection {
    ASC,
    DESC
}
