package com.example.temperate.service.user.aiconversation.exception;

/**
 * 定义 AI 流式请求终止时允许向普通用户公开的稳定原因码和中文原因，不承载上游原始响应。
 */
public enum AiConversationStreamFailureReason {
    UPSTREAM_TOTAL_TIMEOUT("模型响应超过最大允许时间", true),
    UPSTREAM_RATE_LIMITED("上游模型当前受到限流", true),
    UPSTREAM_AUTH_UNAVAILABLE("上游模型认证暂不可用", true),
    UPSTREAM_CONNECTION_CLOSED("上游连接提前中断", true),
    UPSTREAM_NETWORK_ERROR("上游网络通信失败", true),
    UPSTREAM_PROTOCOL_ERROR("上游响应格式无法解析", true),
    UPSTREAM_REASONING_LEVEL_UNSUPPORTED("当前模型不支持所选推理档位", true),
    UPSTREAM_IMAGE_RESOLUTION_UNSUPPORTED("当前模型不支持所选图片分辨率", true),
    UPSTREAM_TOOL_CONFIGURATION_UNSUPPORTED("当前模型不支持所选工具配置", true),
    UPSTREAM_SERVER_ERROR("上游模型服务异常", true),
    USAGE_DATA_UNAVAILABLE("上游未返回完整用量信息", true),
    STREAM_BACKPRESSURE_OVERFLOW("服务端流式转发发生背压异常", true),
    UNKNOWN_STREAM_FAILURE("未识别的流式响应异常", false);

    private final String publicDetail;
    private final boolean known;

    AiConversationStreamFailureReason(
            String publicDetail, boolean known) {
        this.publicDetail = publicDetail;
        this.known = known;
    }

    public String publicDetail() {
        return publicDetail;
    }

    public boolean known() {
        return known;
    }
}
