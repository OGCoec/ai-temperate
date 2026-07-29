package com.example.temperate.service.auth.identity.bloom.config;

import com.example.temperate.service.auth.identity.bloom.IdentityPresenceBloomSettings;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配身份 Bloom 的安全参数与单线程后台重建执行器。
 *
 * <p>单线程执行器把同一实例内的全量构建串行化；跨实例互斥仍由 Redis 构建租约保证。</p>
 */
@Configuration
public class IdentityPresenceBloomConfiguration {

    @Bean
    IdentityPresenceBloomSettings identityPresenceBloomSettings(
            @Value("${app.identity-presence-bloom.enabled:true}") boolean enabled,
            @Value("${app.identity-presence-bloom.capacity:1000000}") int capacity,
            @Value("${app.identity-presence-bloom.hash-count:7}") int hashCount,
            @Value("${app.identity-presence-bloom.counter-bytes:1}") int counterBytes,
            @Value("${app.identity-presence-bloom.counters-per-bucket:1000000}")
                    int countersPerBucket,
            @Value("${app.identity-presence-bloom.build-batch-size:500}") int buildBatchSize,
            @Value("${app.identity-presence-bloom.receipt-shards:256}") int receiptShards,
            @Value("${app.identity-presence-bloom.maximum-elements:100000}")
                    int maximumElements) {
        return new IdentityPresenceBloomSettings(
                enabled,
                capacity,
                hashCount,
                counterBytes,
                countersPerBucket,
                buildBatchSize,
                receiptShards,
                maximumElements);
    }

    @Bean(destroyMethod = "shutdown")
    ScheduledExecutorService identityPresenceBloomExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "identity-presence-bloom-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
