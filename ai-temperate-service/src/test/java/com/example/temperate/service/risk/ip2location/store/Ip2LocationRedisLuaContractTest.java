package com.example.temperate.service.risk.ip2location.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来验证两个 IP2Location Hash 的单凭据写入、随机扣减、字段 TTL 和孤儿修复边界。
 */
class Ip2LocationRedisLuaContractTest {

    @Test
    void writeAndAcquireScriptsKeepBothHashesAligned() throws Exception {
        String write = script("ip2location_write_one.lua");
        String acquire = script("ip2location_acquire.lua");

        assertThat(write)
                .contains(
                        "return 'CAPACITY_REJECTED'",
                        "redis.call('HSET', KEYS[1]",
                        "redis.call('HSET', KEYS[2]",
                        "redis.call('HPEXPIREAT', KEYS[1]",
                        "redis.call('HPEXPIREAT', KEYS[2]");
        assertThat(write)
                .doesNotContain("for index = 1, count")
                .doesNotContain("ip2location_write_batch");
        assertThat(acquire)
                .contains(
                        "redis.call('HRANDFIELD', KEYS[2])",
                        "redis.call('HINCRBY', KEYS[2], field, -1)",
                        "'HPEXPIRETIME', KEYS[1]",
                        "'HPEXPIRETIME', KEYS[2]",
                        "redis.call('HDEL', KEYS[1], field)",
                        "redis.call('HDEL', KEYS[2], field)");
    }

    @Test
    void listingUsesHscanInsteadOfLoadingTheWholeHash() throws Exception {
        String scan = script("ip2location_scan.lua");

        assertThat(scan)
                .contains("redis.call('HSCAN', KEYS[1]")
                .contains("redis.call('HMGET', KEYS[2]")
                .doesNotContain("HGETALL");
    }

    @Test
    void javaStorePipelinesSingleCredentialScriptsInFiftyItemBatches() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/temperate/service/risk/ip2location/store/impl",
                "RedisIp2LocationApiKeyStore.java"));

        assertThat(source)
                .contains("PIPELINE_BATCH_SIZE = 50")
                .contains("executePipelined")
                .contains("ip2location_write_one.lua")
                .doesNotContain("ip2location_write_batch.lua");
    }

    private static String script(String name) throws Exception {
        return Files.readString(Path.of(
                "src/main/resources/lua/network-risk",
                name));
    }
}
