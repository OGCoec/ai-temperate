package com.example.temperate.service.user.apikey.model;

import com.example.temperate.service.user.apikey.authentication.ApiKeyPrincipal;
import java.util.List;

/**
 * 该服务是来为已认证 API Key 列出可用于公开 Chat Completions 或 Responses 的模型，不参与 Key 认证或上游模型发现。
 */
public interface ApiKeyModelDiscoveryService {

    List<AuthorizedModel> list(ApiKeyPrincipal principal);

    /** 授权模型只保留公开协议所需的模型名称与创建 Unix 时间戳。 */
    record AuthorizedModel(String modelName, long createdEpochSeconds) {
    }
}
