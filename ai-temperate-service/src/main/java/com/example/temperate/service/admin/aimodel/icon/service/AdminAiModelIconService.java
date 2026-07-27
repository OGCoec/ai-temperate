package com.example.temperate.service.admin.aimodel.icon.service;

import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconPatchCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconRemoteCreateCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AdminAiModelIconUploadCommand;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconPageResult;
import com.example.temperate.service.admin.aimodel.icon.dto.AiModelIconResult;

/**
 * 定义管理员查询、创建、修改、替换和删除模型图标资源的业务能力。
 *
 * <p>本地文件的 OSS 操作在数据库事务之外完成，数据库写入由独立持久化 Service 提供短事务。</p>
 */
public interface AdminAiModelIconService {

    int MAX_FILE_BYTES = 2 * 1024 * 1024;

    AiModelIconPageResult list(int pageNum, int pageSize);

    AiModelIconResult detail(String publicId);

    AiModelIconResult createRemote(AdminAiModelIconRemoteCreateCommand command);

    AiModelIconResult createUpload(AdminAiModelIconUploadCommand command);

    AiModelIconResult patch(String publicId, AdminAiModelIconPatchCommand command);

    AiModelIconResult replaceFile(
            String publicId,
            byte[] bytes,
            String contentType);

    void delete(String publicId);
}
