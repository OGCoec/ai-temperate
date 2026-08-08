package com.example.temperate.service.user.aiconversation.video;

/**
 * 定义第一阶段支持的五种视频生成操作，并作为 xAI 模式策略注册表的稳定选择键。
 */
public enum AiConversationVideoMode {
    TEXT_TO_VIDEO,
    IMAGE_TO_VIDEO,
    REFERENCE_TO_VIDEO,
    VIDEO_EDIT,
    VIDEO_EXTEND
}
