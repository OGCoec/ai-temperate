package com.example.temperate.service.user.apikey.config;

import com.example.temperate.common.bloom.counting.CountingBloomLayout;
import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.bloom.CountingBloomNamespace;
import com.example.temperate.service.user.aiconversation.config.AiInferenceProperties;
import com.example.temperate.service.user.apikey.idempotency.ApiKeyCreateIdempotencyHasher;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 该配置是来注册并校验阶段 S 的 API Key 配置边界，业务实现只依赖绑定后的强类型对象而不直接读取环境变量。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ApiKeyProperties.class)
public class ApiKeyConfiguration {

    /**
     * 创建锁摘要复用经过启动校验的 API Key 根密钥，并由摘要器的 purpose 做业务用途隔离。
     */
    @Bean
    ApiKeyCreateIdempotencyHasher apiKeyCreateIdempotencyHasher(
            ApiKeyProperties properties) {
        return new ApiKeyCreateIdempotencyHasher(
                Base64.getDecoder().decode(properties.getHmacSecretBase64()));
    }

    /**
     * 固定 v1 的所有 Redis Key 都经工厂生成；通用引擎只接收不可变命名空间，不自行拼接业务 Key。
     */
    @Bean
    @Qualifier("apiKeyCountingBloomNamespace")
    CountingBloomNamespace apiKeyCountingBloomNamespace(
            RedisKeyFactory keyFactory,
            ApiKeyProperties properties) {
        ApiKeyProperties.Bloom bloom = properties.getBloom();
        CountingBloomLayout layout = new CountingBloomLayout(
                bloom.getCapacity(),
                bloom.getHashCount(),
                bloom.getCounterBytes(),
                bloom.getCountersPerBucket());
        List<String> buckets = new ArrayList<>(layout.bucketCount());
        for (int bucket = 0; bucket < layout.bucketCount(); bucket++) {
            buckets.add(keyFactory.apiKeyBloomBucketKey(bucket));
        }
        List<String> receipts = new ArrayList<>(bloom.getReceiptShards());
        for (int shard = 0; shard < bloom.getReceiptShards(); shard++) {
            receipts.add(keyFactory.apiKeyBloomReceiptKey(shard));
        }
        return new CountingBloomNamespace(
                layout,
                keyFactory.apiKeyBloomMetaKey(),
                buckets,
                receipts,
                keyFactory.apiKeyBloomPositiveMutationKey());
    }

    /**
     * 公开 Chat 与 Responses 共用的专用 WebClient 固定指向服务端 8317；客户端 Bearer 永不进入默认请求头。
     */
    @Bean
    @Qualifier("apiChatUpstreamWebClient")
    WebClient apiChatUpstreamWebClient(
            WebClient.Builder builder,
            AiInferenceProperties properties,
            ApiKeyProperties apiKeyProperties) {
        if (apiKeyProperties.isEnabled() && !isApprovedLoopbackUpstream(properties.baseUrl())) {
            throw new IllegalStateException(
                    "Public inference APIs require the fixed 127.0.0.1:8317 upstream");
        }
        WebClient.Builder dedicated = builder.clone().baseUrl(properties.baseUrl());
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            dedicated.defaultHeaders(headers -> headers.setBearerAuth(properties.apiKey()));
        }
        return dedicated.build();
    }

    private static boolean isApprovedLoopbackUpstream(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() == 8317
                    && (path == null || path.isEmpty() || "/".equals(path))
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
