package com.example.temperate.service.admin.aimodel.service;

import com.example.temperate.service.admin.aimodel.dto.AdminAiModelBatchStatusResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelCreateCommand;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelDetailResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPatchCommand;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelPageResult;
import com.example.temperate.service.admin.aimodel.dto.AdminAiModelResult;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortDirection;
import com.example.temperate.service.admin.aimodel.domain.AiModelSortPriority;
import java.util.List;

/**
 * 定义管理员对 AI 模型执行查询、新增、并发安全字段编辑和启停的业务能力。
 *
 * <p>该接口刻意不提供模型物理删除；停用是模型退出可用范围的唯一方式。</p>
 */
public interface AdminAiModelService {

    AdminAiModelPageResult list(
            int pageNum,
            int pageSize,
            String keyword,
            Boolean enabled,
            AiModelSortPriority sortPriority,
            AiModelSortDirection direction);

    AdminAiModelDetailResult detail(String publicId);

    AdminAiModelDetailResult patch(
            String publicId,
            long expectedVersion,
            AdminAiModelPatchCommand command);

    AdminAiModelResult create(AdminAiModelCreateCommand command);

    AdminAiModelResult setEnabled(String publicId, boolean enabled);

    AdminAiModelBatchStatusResult setEnabledBatch(
            List<String> publicIds,
            boolean enabled);
}
