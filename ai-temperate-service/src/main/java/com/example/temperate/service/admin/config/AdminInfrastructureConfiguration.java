package com.example.temperate.service.admin.config;

import com.example.temperate.service.admin.config.properties.AdminProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用管理员配置属性绑定，使服务层文件、hCaptcha、流程和会话组件共享同一组受校验参数。
 */
@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class AdminInfrastructureConfiguration {
}
