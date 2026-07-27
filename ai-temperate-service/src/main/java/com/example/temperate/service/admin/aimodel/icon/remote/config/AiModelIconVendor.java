package com.example.temperate.service.admin.aimodel.icon.remote.config;

/**
 * 标识允许使用官方 SVG 兼容档位的 AI 厂商。
 *
 * <p>枚举值只用于服务端可信域名配置与审计边界，不会写入数据库或暴露为模型图标 API 字段。</p>
 */
public enum AiModelIconVendor {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    XAI,
    DEEPSEEK,
    ZHIPU,
    MOONSHOT,
    ALIBABA_QWEN
}
