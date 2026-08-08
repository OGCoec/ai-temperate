package com.example.temperate.service.user.aiconversation.model.stream.xai.video;

import com.example.temperate.service.user.aiconversation.video.AiConversationVideoMode;

/**
 * 定义一种 xAI 视频模式如何执行边界校验并生成该模式唯一允许的 REST 请求字段。
 */
public interface XaiVideoOperationStrategy {

    AiConversationVideoMode mode();

    XaiVideoStartRequest buildRequest(XaiVideoOperationContext context);
}
