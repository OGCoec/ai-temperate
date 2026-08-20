package com.example.temperate.web.auth.oauth.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 装配 GitHub/Google 固定客户端注册、Spring Authorization Code 换码客户端与 Google JWS 验证器。
 *
 * <p>客户端注册全部来自受校验环境配置；Provider Token 只停留在当前方法调用内，不注册授权客户端仓库，
 * 从而不会被 Spring Session 或数据库长期保存。</p>
 */
@Configuration
@EnableConfigurationProperties(OAuthClientProperties.class)
@ConditionalOnProperty(prefix = "app.oauth", name = "enabled", havingValue = "true")
public class OAuthClientConfiguration {

    @Bean("githubOAuthClientRegistration")
    ClientRegistration githubOAuthClientRegistration(OAuthClientProperties properties) {
        return ClientRegistration.withRegistrationId("github")
                .clientId(properties.github().clientId())
                .clientSecret(properties.github().clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.callbackUri(
                        com.example.temperate.service.auth.oauth.domain.OAuthProvider.GITHUB)
                        .toString())
                .scope("read:user", "user:email")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .tokenUri("https://github.com/login/oauth/access_token")
                .userInfoUri("https://api.github.com/user")
                .userNameAttributeName("id")
                .clientName("GitHub")
                .build();
    }

    @Bean("googleOAuthClientRegistration")
    ClientRegistration googleOAuthClientRegistration(OAuthClientProperties properties) {
        return ClientRegistration.withRegistrationId("google")
                .clientId(properties.google().clientId())
                .clientSecret(properties.google().clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(properties.callbackUri(
                        com.example.temperate.service.auth.oauth.domain.OAuthProvider.GOOGLE)
                        .toString())
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri("https://accounts.google.com")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
            oauthAuthorizationCodeTokenResponseClient() {
        return new RestClientAuthorizationCodeTokenResponseClient();
    }

    @Bean("githubOAuthWebClient")
    WebClient githubOAuthWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "ai-temperate-oauth")
                .build();
    }

    @Bean("googleBrowserJwtDecoder")
    JwtDecoder googleBrowserJwtDecoder(
            OAuthClientProperties properties,
            @Qualifier("googleOAuthClientRegistration") ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                registration.getProviderDetails().getJwkSetUri()).build();
        // 时间、发行方和 audience 必须全部验证；策略层还会再次约束 nonce、邮箱和 Subject。
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<String>("iss", issuer ->
                        "https://accounts.google.com".equals(issuer)
                                || "accounts.google.com".equals(issuer)),
                new JwtClaimValidator<List<String>>("aud", audience ->
                        audience != null
                                && audience.contains(properties.google().clientId()))));
        return decoder;
    }

    @Bean("googleNativeJwtDecoder")
    JwtDecoder googleNativeJwtDecoder(
            OAuthClientProperties properties,
            @Qualifier("googleOAuthClientRegistration") ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                registration.getProviderDetails().getJwkSetUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<String>("iss", issuer ->
                        "https://accounts.google.com".equals(issuer)
                                || "accounts.google.com".equals(issuer)),
                new JwtClaimValidator<List<String>>("aud", audience ->
                        audience != null
                                && audience.contains(
                                        properties.google().androidServerClientId()))));
        return decoder;
    }
}
