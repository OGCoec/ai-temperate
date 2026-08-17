package com.example.temperate.service.user.apikey.model.impl;

import com.example.temperate.model.ai.enums.AiModelCapabilityCode;
import com.example.temperate.service.admin.aimodel.cache.AiModelCacheService;
import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryException;
import com.example.temperate.service.user.apikey.model.ApiKeyModelDiscoveryService;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来把认证主体内的有效授权 ID 与启用模型快照求交集，确保公开模型列表和 Chat 请求使用同一授权事实。
 */
@Service
public final class ApiKeyModelDiscoveryServiceImpl implements ApiKeyModelDiscoveryService {

    private final AiModelCacheService modelCacheService;

    public ApiKeyModelDiscoveryServiceImpl(AiModelCacheService modelCacheService) {
        this.modelCacheService = Objects.requireNonNull(modelCacheService);
    }

    @Override
    public List<AuthorizedModel> list(ApiKeyPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        try {
            // API Key 的授权集合在认证阶段已从有效 grant 批量加载；此处禁止为每个模型再次发起数据库 I/O。
            return modelCacheService.getOrLoadEnabledSnapshot().models().stream()
                    .filter(model -> principal.modelIds().contains(model.id()))
                    .filter(model -> model.capabilities().contains(
                            AiModelCapabilityCode.CHAT_COMPLETIONS))
                    .map(model -> new AuthorizedModel(
                            model.modelName(), model.createdEpochSeconds()))
                    .sorted(Comparator.comparing(AuthorizedModel::modelName))
                    .toList();
        } catch (RuntimeException exception) {
            if (exception instanceof ApiKeyModelDiscoveryException) {
                throw exception;
            }
            // 模型目录异常时不能退化为返回未经验证的授权名称，避免把禁用或错误配置模型暴露给客户端。
            throw ApiKeyModelDiscoveryException.unavailable(exception);
        }
    }
}
