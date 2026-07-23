package com.example.temperate.service.registration.config;

import com.example.temperate.service.registration.verification.delivery.util.twilio.TwilioWhatsAppMessagingUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.http.TwilioRestClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配 Twilio WhatsApp 验证码模板投递工具，并复用统一 Twilio REST 客户端和项目 JSON 序列化器。
 *
 * <p>只有 Sender 和 Content SID 均存在时才注册工具；未配置环境不会创建半可用供应商实现，
 * 也不会把 Sandbox Sender 或模板标识写死到 Java 代码。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(name = "verificationTwilioRestClient")
@ConditionalOnExpression(
        "!'${app.registration.whatsapp.from:}'.isEmpty() && "
                + "!'${app.registration.whatsapp.content-sid:}'.isEmpty()")
public class TwilioWhatsAppConfiguration {

    @Bean
    TwilioWhatsAppMessagingUtil twilioWhatsAppMessagingUtil(
            @Qualifier("verificationTwilioRestClient") TwilioRestClient twilioRestClient,
            @Value("${app.registration.whatsapp.from}") String from,
            @Value("${app.registration.whatsapp.content-sid}") String contentSid,
            @Value("${app.registration.whatsapp.status-callback-url:}") String statusCallbackUrl,
            ObjectMapper objectMapper) {
        return new TwilioWhatsAppMessagingUtil(
                twilioRestClient, from, contentSid, statusCallbackUrl, objectMapper);
    }
}
