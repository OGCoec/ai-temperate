package com.example.temperate.service.admin.aimodel.discovery.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 定义从固定 CLIProxyAPI 模型列表端点读取原始 JSON 文档的后端客户端边界。
 */
public interface CliProxyModelClient {

    JsonNode fetchModels();
}
