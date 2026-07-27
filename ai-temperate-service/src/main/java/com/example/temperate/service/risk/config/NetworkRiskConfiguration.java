package com.example.temperate.service.risk.config;

import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.ip2location.security.Ip2LocationApiKeyProtector;
import com.example.temperate.service.risk.security.NetworkRiskIdentifier;
import com.example.temperate.service.risk.webrtc.security.WebRtcIpProtector;
import com.example.temperate.service.risk.webrtc.validation.WebRtcIpNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * 装配网络风险密钥派生、供应商 WebClient 与外部查询并发舱壁。
 *
 * <p>两个供应商客户端禁止跨主机重定向并限制响应体大小；Semaphore 只约束缓存未命中的外部查询，
 * 防止慢供应商耗尽 MVC 请求线程。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NetworkRiskProperties.class)
public class NetworkRiskConfiguration {

    @Bean
    NetworkRiskIdentifier networkRiskIdentifier(NetworkRiskProperties properties) {
        byte[] secret = decodeOrCreateDisabledSecret(
                properties.hmacSecretBase64(),
                properties.mode());
        return new NetworkRiskIdentifier(new HmacSha256Identifier(secret));
    }

    @Bean
    Ip2LocationApiKeyProtector ip2LocationApiKeyProtector(
            NetworkRiskProperties properties,
            ObjectMapper objectMapper) {
        return new Ip2LocationApiKeyProtector(
                Base64.getEncoder().encodeToString(decodeOrCreateDisabledSecret(
                        properties.ip2LocationApiKeyEncryptionKeyBase64(),
                        properties.mode())),
                objectMapper);
    }

    @Bean
    WebRtcIpProtector webRtcIpProtector(
            NetworkRiskProperties properties,
            ObjectMapper objectMapper) {
        byte[] secret = decodeOrCreateDisabledSecret(
                properties.webRtc().ipEncryptionKeyBase64(),
                properties.mode());
        return new WebRtcIpProtector(
                Base64.getEncoder().encodeToString(secret),
                objectMapper);
    }

    @Bean
    WebRtcIpNormalizer webRtcIpNormalizer() {
        return new WebRtcIpNormalizer();
    }

    @Bean("ip2LocationRiskWebClient")
    WebClient ip2LocationRiskWebClient(NetworkRiskProperties properties) {
        return providerWebClient(
                properties.ip2LocationBaseUrl().toString(),
                properties.lookupTimeout());
    }

    @Bean("ipingRiskWebClient")
    WebClient ipingRiskWebClient(NetworkRiskProperties properties) {
        return providerWebClient(
                properties.ipingBaseUrl().toString(),
                properties.lookupTimeout());
    }

    @Bean("networkRiskLookupBulkhead")
    Semaphore networkRiskLookupBulkhead(NetworkRiskProperties properties) {
        return new Semaphore(properties.maxConcurrentLookups(), true);
    }

    private static WebClient providerWebClient(String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) timeout.toMillis())
                .responseTimeout(timeout);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(64 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    private static byte[] decodeOrCreateDisabledSecret(
            String encoded,
            NetworkRiskMode mode) {
        if (encoded != null && !encoded.isBlank()) {
            return Base64.getDecoder().decode(encoded);
        }
        if (mode != NetworkRiskMode.DISABLED) {
            throw new IllegalStateException("Network risk secret is required when risk processing is enabled.");
        }
        /*
         * 关闭模式仅为完成 Bean 装配生成进程内随机材料；它不会写入配置、日志或 Redis，
         * 也不能替代 OBSERVE/ENFORCE 所要求的部署 Secret。
         */
        byte[] ephemeral = new byte[32];
        new SecureRandom().nextBytes(ephemeral);
        return ephemeral;
    }
}
