package com.example.temperate.web.audit.access.config;

import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 为访问审计发布消息配置 JSON 转换器，使 RabbitMQ 消息保持稳定的跨进程结构而非 Java 原生序列化。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public class AccessAuditRabbitTemplateConfiguration {

    @Bean
    RabbitTemplateCustomizer accessAuditRabbitTemplateCustomizer(
            @Qualifier("accessAuditMessageConverter") MessageConverter messageConverter) {
        return rabbitTemplate -> rabbitTemplate.setMessageConverter(messageConverter);
    }
}
