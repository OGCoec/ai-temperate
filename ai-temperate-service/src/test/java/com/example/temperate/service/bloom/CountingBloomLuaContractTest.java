package com.example.temperate.service.bloom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来静态锁定计数 Bloom Lua 的 Receipt 幂等、防溢出、防下溢、三态查询和 positive mutation 原子激活不变量。
 */
final class CountingBloomLuaContractTest {

    private static final Path SCRIPT_ROOT = Path.of(
            "src/main/resources/redis-scripts/counting-bloom");

    @Test
    void addAndRemovePreflightEveryCounterBeforeMutation() throws IOException {
        String add = read("add.lua");
        String remove = read("remove.lua");

        assertThat(add)
                .contains("SISMEMBER")
                .contains("__ait_counting_bloom_receipt__")
                .contains("bucket_length_invalid")
                .contains("counter >= maximumCounter")
                .contains("counterBytes == 1 and 255 or 65535")
                .contains("counter_overflow")
                .contains("SADD");
        assertThat(remove)
                .contains("SISMEMBER")
                .contains("readCounter(KEYS[bucket_key_index], offset) == 0")
                .contains("counter_underflow")
                .contains("SREM");
    }

    @Test
    void queryOnlyProducesDefinitiveAnswersWhileActive() throws IOException {
        String query = read("query.lua");

        assertThat(query)
                .contains("state ~= 'ACTIVE'")
                .contains("STRLEN")
                .contains("bucket_length_invalid")
                .contains("return 2")
                .contains("return 0")
                .contains("return 1");
    }

    @Test
    void positiveMutationStoresProtectedIdentifierAndActivationChecksItAtomically()
            throws IOException {
        String begin = read("begin-positive-mutation.lua");
        String finish = read("finish-positive-mutation.lua");
        String activate = read("activate.lua");

        assertThat(begin)
                .contains("HSET', KEYS[2], ARGV[1], ARGV[2]")
                .contains("positive_mutation_pending")
                .contains("HLEN");
        assertThat(finish)
                .contains("ARGV[2] ~= '1'")
                .contains("positive_mutation_incomplete")
                .contains("mutation_resume_active");
        assertThat(activate)
                .contains("build_fence")
                .contains("return -4")
                .contains("state') ~= 'READY'")
                .contains("verified') ~= '1'")
                .contains("HLEN', KEYS[3]) ~= 0")
                .contains("state', 'ACTIVE'");
    }

    @Test
    void everyLeaderExclusiveBuildWriteChecksLeaseAndMonotonicFence()
            throws IOException {
        assertThat(read("acquire-leader.lua"))
                .contains("INCR', KEYS[2]")
                .contains("tostring(epoch) .. ':' .. ARGV[1]");
        assertThat(read("build-unlink.lua"))
                .contains("GET', KEYS[1]) ~= ARGV[1]")
                .contains("return -4");
        assertThat(read("begin-build.lua"))
                .contains("GET', KEYS[1]) ~= ARGV[1]")
                .contains("'build_fence', ARGV[2]");

        for (String script : new String[] {
                "initialize-bucket.lua",
                "initialize-receipt.lua",
                "delete-recovered-mutations.lua",
                "mark-ready.lua",
                "activate.lua",
                "mark-build-degraded.lua"
        }) {
            assertThat(read(script))
                    .as(script)
                    .contains("GET', KEYS[1]) ~= ARGV[1]")
                    .contains("build_fence")
                    .contains("return -4");
        }
        assertThat(read("add.lua"))
                .contains("BUILD_FENCE")
                .contains("redis.call('GET', KEYS[#KEYS])")
                .contains("build_fence")
                .contains("return -4");
    }

    private static String read(String name) throws IOException {
        return Files.readString(SCRIPT_ROOT.resolve(name), StandardCharsets.UTF_8);
    }
}
