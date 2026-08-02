package com.example.temperate.service.user.aimodel.service;

import com.example.temperate.service.user.aimodel.dto.UserAiModelPageResult;
import com.example.temperate.service.user.aimodel.dto.UserAiModelResult;

/**
 * 定义普通用户分页读取、按完整词元搜索已启用 AI 模型及按公共 ID 查看详情的业务边界。
 */
public interface UserAiModelCatalogService {

    UserAiModelPageResult list(int pageNum, int pageSize, String keyword);

    UserAiModelResult detail(String publicId);
}
