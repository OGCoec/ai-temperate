package com.example.temperate.service.user.aiconversation.image;

/**
 * 区分一次图片任务是从文字创建新图，还是使用已校验的输入图片执行编辑。
 */
public enum AiConversationImageAction {
    GENERATE,
    EDIT
}
