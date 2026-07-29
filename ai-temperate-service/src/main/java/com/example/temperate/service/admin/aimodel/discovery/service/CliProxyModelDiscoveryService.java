package com.example.temperate.service.admin.aimodel.discovery.service;

import com.example.temperate.service.admin.aimodel.discovery.dto.CliProxyModelDiscoveryResult;

/**
 * 定义管理员读取并匹配 CLIProxyAPI 当前模型列表的只读业务能力。
 */
public interface CliProxyModelDiscoveryService {

    CliProxyModelDiscoveryResult discoverModels();
}
