package com.example.temperate.service.registration.config;

import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiMailUtil;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphApiProperties;
import com.example.temperate.service.registration.verification.delivery.util.microsoft.MicrosoftGraphOAuthTokenUtil;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 装配个人 Microsoft 账户通过 Graph API 投递邮箱验证码所需的响应式 OAuth 与邮件组件。
 *
 * <p>只要三项 OAuth 凭据齐全就注册 Microsoft 策略；发件身份由访问令牌对应的 {@code /me}
 * 资源决定，不再要求或读取显式发件地址。OAuth 与 sendMail 使用独立超时，有限业务重试仍由
 * RabbitMQ 统一控制。</p>
 */
@Configuration
@ConditionalOnExpression(
        "!'${app.registration.microsoft-graph.oauth.client-id:}'.isEmpty()"
                + " && !'${app.registration.microsoft-graph.oauth.client-secret:}'.isEmpty()"
                + " && !'${app.registration.microsoft-graph.oauth.refresh-token:}'.isEmpty()")
public class MicrosoftGraphApiConfiguration {

    @Bean
    MicrosoftGraphApiProperties microsoftGraphApiProperties(
            @Value("${app.registration.microsoft-graph.oauth.client-id}") String clientId,
            @Value("${app.registration.microsoft-graph.oauth.client-secret}")
                    String clientSecret,
            @Value("${app.registration.microsoft-graph.oauth.refresh-token}")
                    String refreshToken,
            @Value("${app.registration.microsoft-graph.oauth.token-uri:"
                    + "https://login.microsoftonline.com/consumers/oauth2/v2.0/token}")
                    String tokenUri,
            @Value("${app.registration.microsoft-graph.oauth.scope:"
                    + "offline_access https://graph.microsoft.com/Mail.Send}")
                    String scope,
            @Value("${app.registration.microsoft-graph.send-uri:"
                    + "https://graph.microsoft.com/v1.0/me/sendMail}")
                    String sendUri,
            @Value("${app.registration.microsoft-graph.oauth-timeout:"
                    + "${app.registration.microsoft-graph.request-timeout:10s}}")
                    Duration oauthTimeout,
            @Value("${app.registration.microsoft-graph.send-timeout:"
                    + "${app.registration.microsoft-graph.request-timeout:10s}}")
                    Duration sendTimeout) {
        return new MicrosoftGraphApiProperties(
                clientId,
                clientSecret,
                refreshToken,
                tokenUri,
                scope,
                sendUri,
                oauthTimeout,
                sendTimeout);
    }

    @Bean
    MicrosoftGraphOAuthTokenUtil microsoftGraphOAuthTokenUtil(
            WebClient.Builder webClientBuilder,
            MicrosoftGraphApiProperties properties) {
        return new MicrosoftGraphOAuthTokenUtil(webClientBuilder.build(), properties);
    }

    @Bean
    MicrosoftGraphApiMailUtil microsoftGraphApiMailUtil(
            WebClient.Builder webClientBuilder,
            MicrosoftGraphApiProperties properties,
            MicrosoftGraphOAuthTokenUtil tokenUtil) {
        return new MicrosoftGraphApiMailUtil(
                webClientBuilder.build(), properties, tokenUtil);
    }
}
