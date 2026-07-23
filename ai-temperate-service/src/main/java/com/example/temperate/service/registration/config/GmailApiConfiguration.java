package com.example.temperate.service.registration.config;

import com.example.temperate.service.registration.verification.delivery.util.gmail.GmailApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.gmail.GmailApiProperties;
import com.example.temperate.service.registration.verification.delivery.util.gmail.GmailOAuthTokenUtil;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 装配 Gmail API 邮箱验证码投递所需的 OAuth 和 HTTP 工具。
 *
 * <p>只有完整 Gmail 凭据都存在时才启用，避免没有配置测试凭据的环境在启动阶段强制创建邮箱策略。</p>
 */
@Configuration
@ConditionalOnExpression(
        "!'${app.registration.gmail.oauth.client-id:}'.isEmpty()"
                + " && !'${app.registration.gmail.oauth.client-secret:}'.isEmpty()"
                + " && !'${app.registration.gmail.oauth.refresh-token:}'.isEmpty()"
                + " && !'${app.registration.gmail.from:}'.isEmpty()")
public class GmailApiConfiguration {

    @Bean
    GmailApiProperties gmailApiProperties(
            @Value("${app.registration.gmail.oauth.client-id}") String clientId,
            @Value("${app.registration.gmail.oauth.client-secret}") String clientSecret,
            @Value("${app.registration.gmail.oauth.refresh-token}") String refreshToken,
            @Value("${app.registration.gmail.from}") String fromAddress,
            @Value("${app.registration.gmail.oauth.token-uri:https://oauth2.googleapis.com/token}")
                    String tokenUri,
            @Value("${app.registration.gmail.send-uri:https://gmail.googleapis.com/gmail/v1/users/me/messages/send}")
                    String sendUri,
            @Value("${app.registration.gmail.request-timeout:5s}") Duration requestTimeout) {
        return new GmailApiProperties(
                clientId, clientSecret, refreshToken, fromAddress, tokenUri, sendUri, requestTimeout);
    }

    @Bean
    GmailOAuthTokenUtil gmailOAuthTokenUtil(
            WebClient.Builder webClientBuilder, GmailApiProperties properties) {
        return new GmailOAuthTokenUtil(webClientBuilder.build(), properties);
    }

    @Bean
    GmailApiMailUtil gmailApiMailUtil(
            WebClient.Builder webClientBuilder,
            GmailApiProperties properties,
            GmailOAuthTokenUtil tokenUtil) {
        return new GmailApiMailUtil(webClientBuilder.build(), properties, tokenUtil);
    }
}
