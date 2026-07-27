package com.example.temperate.service.admin.aimodel.icon.remote;

/**
 * 定义外部模型图标 URL 的安全下载和真实图片验证边界。
 */
public interface AiModelIconRemoteImageValidator {

    ValidatedRemoteIcon validate(String url);
}
