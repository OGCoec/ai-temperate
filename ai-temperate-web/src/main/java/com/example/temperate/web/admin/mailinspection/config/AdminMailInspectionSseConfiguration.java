package com.example.temperate.web.admin.mailinspection.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册管理员邮件检查 SSE 配置属性，不负责连接或事件业务。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminMailInspectionSseProperties.class)
public class AdminMailInspectionSseConfiguration {
}
