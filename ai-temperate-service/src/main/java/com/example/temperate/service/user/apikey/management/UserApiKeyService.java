package com.example.temperate.service.user.apikey.management;

import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.CreateCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Created;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Detail;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.Page;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.ReplaceModelsCommand;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementModels.UpdateCommand;

/**
 * 该服务是来编排当前用户 API Key 的创建、稳定游标读取、乐观锁更新、模型全量替换和不可恢复软删除。
 */
public interface UserApiKeyService {

    Created create(long loginIdentityId, CreateCommand command);

    Page list(long loginIdentityId, String cursor, int pageSize);

    Detail detail(long loginIdentityId, String apiKeyPublicId);

    Detail update(
            long loginIdentityId,
            String apiKeyPublicId,
            long expectedVersion,
            UpdateCommand command);

    Detail replaceModels(
            long loginIdentityId,
            String apiKeyPublicId,
            long expectedVersion,
            ReplaceModelsCommand command);

    void delete(long loginIdentityId, String apiKeyPublicId, long expectedVersion);
}
