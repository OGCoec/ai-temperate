package com.example.temperate.web.apichat;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * 该配置是来声明公开 Chat Completions 的 Bearer API Key OpenAPI 安全方案，文档只使用脱敏占位符而不生成真实 Key 示例。
 */
@Configuration
@SecurityScheme(
        name = "apiKeyBearer",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "sk-***",
        description = "使用创建接口仅返回一次的 API Key，例如 Bearer sk-***。")
public class ApiChatOpenApiConfiguration {
}
