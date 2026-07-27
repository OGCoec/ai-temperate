package com.example.temperate.service.risk.ipintel.cache.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.risk.ipintel.domain.IpIntelligenceSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 验证 IP 情报缓存只依赖 Redis 是否返回合法 v2 JSON，并清理损坏或旧版本值。
 */
class RedisIpIntelligenceCacheTest {

    private static final HmacIdentifier IP_DIGEST =
            new HmacSha256Identifier(
                    "redis-ip-cache-test-secret-0123456789".getBytes())
                    .identify("198.51.100.10");

    @Test
    void readableV2ValueIsValidWithoutConsultingAStoredEvaluationTime()
            throws Exception {
        Fixture fixture = fixture();
        when(fixture.values().get(fixture.key())).thenReturn("""
                {"schemaVersion":2,"trustScore":58,"countryCode":"US",
                 "asn":8075,"latitude":41.85003,"longitude":-87.65005,
                 "networkType":"DATA_CENTER","scoreIncludesNetworkRisk":true,
                 "source":"IPING"}
                """);

        Optional<IpIntelligenceSnapshot> result =
                fixture.cache().find(IP_DIGEST);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().trustScore()).isEqualTo(58);
    }

    @Test
    void oldSchemaAndDamagedJsonAreUnlinked() {
        for (String value : new String[] {
                "{\"schemaVersion\":1,\"trustScore\":58}",
                "not-json"
        }) {
            Fixture fixture = fixture();
            when(fixture.values().get(fixture.key())).thenReturn(value);

            assertThat(fixture.cache().find(IP_DIGEST)).isEmpty();
            verify(fixture.redis()).unlink(fixture.key());
        }
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisKeyFactory keyFactory = new RedisKeyFactory("test");
        RedisIpIntelligenceCache cache = new RedisIpIntelligenceCache(
                redis,
                keyFactory,
                new ObjectMapper());
        return new Fixture(
                cache,
                redis,
                values,
                keyFactory.ipIntelligenceCacheKey(IP_DIGEST));
    }

    private record Fixture(
            RedisIpIntelligenceCache cache,
            StringRedisTemplate redis,
            ValueOperations<String, String> values,
            String key) {
    }
}
