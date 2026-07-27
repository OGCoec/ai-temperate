package com.example.temperate.service.risk.ip2location.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 验证两个 IP2Location Hash 的写入、随机扣减、字段 TTL 恢复和孤儿修复位于有界 Lua 原子边界内。
 */
class Ip2LocationRedisLuaContractTest {

    @Test
    void writeAndAcquireScriptsKeepBothHashesAligned() throws Exception {
        String write = script("ip2location_write_batch.lua");
        String acquire = script("ip2location_acquire.lua");

        assertThat(write)
                .contains(
                        "count = tonumber(ARGV[2])",
                        "redis.call('HSET', KEYS[1]",
                        "redis.call('HSET', KEYS[2]",
                        "redis.call('HPEXPIREAT', KEYS[1]",
                        "redis.call('HPEXPIREAT', KEYS[2]");
        assertThat(acquire)
                .contains(
                        "redis.call('HRANDFIELD', KEYS[2])",
                        "redis.call('HINCRBY', KEYS[2], field, -1)",
                        "redis.call('HPEXPIRETIME', KEYS[1]",
                        "redis.call('HPEXPIRETIME', KEYS[2]",
                        "redis.call('HDEL', KEYS[1], field)",
                        "redis.call('HDEL', KEYS[2], field)");
    }

    @Test
    void listingUsesHscanInsteadOfLoadingTheWholeHash() throws Exception {
        String scan = script("ip2location_scan.lua");

        assertThat(scan)
                .contains("redis.call('HSCAN', KEYS[1]")
                .doesNotContain("HGETALL");
    }

    private static String script(String name) throws Exception {
        return Files.readString(Path.of(
                "src/main/resources/lua/network-risk",
                name));
    }
}
