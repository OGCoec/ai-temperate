package com.example.temperate.common.id.snowflake.component;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证混合 ID 工作者在并发发号时始终输出完整 128 位值且不会在同一实例内重复。
 */
final class HybridSemaphoreIdWorkerTest {

    @Test
    void generatesUniqueTwentyTwoCharacterIdsConcurrently() throws Exception {
        HybridSemaphoreIdWorker worker = new HybridSemaphoreIdWorker(1L, 1L);
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        int taskCount = 2_000;
        Set<String> ids = ConcurrentHashMap.newKeySet(taskCount);
        CountDownLatch completed = new CountDownLatch(taskCount);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            for (int index = 0; index < taskCount; index++) {
                executor.execute(() -> {
                    ids.add(codec.encode(worker.nextId()));
                    completed.countDown();
                });
            }

            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertEquals(taskCount, ids.size());
            assertTrue(ids.stream().allMatch(
                    value -> value.matches(HybridBase64UrlCodec.FORMAT)));
        } finally {
            executor.shutdownNow();
        }
    }
}
