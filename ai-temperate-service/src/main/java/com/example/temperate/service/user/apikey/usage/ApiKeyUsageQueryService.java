package com.example.temperate.service.user.apikey.usage;

import com.example.temperate.service.user.apikey.usage.ApiKeyUsageModels.Page;
import java.time.OffsetDateTime;

/**
 * 该服务是来验证当前用户对 API Key 的所有权，并按固定时间范围查询权威逐次调用与扣费记录。
 */
public interface ApiKeyUsageQueryService {

    Page query(
            long loginIdentityId,
            byte[] apiKeyId,
            OffsetDateTime from,
            OffsetDateTime to,
            String cursor,
            int pageSize);
}
