package com.example.temperate.service.admin.mailinspection.event.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 验证 Pub/Sub 唤醒失败只记录低基数指标且不会反向撤销已经写入 Redis 的权威状态。
 */
final class RedisMailInspectionJobEventPublisherImplTest {

    @Test
    void recordsFailureWithoutPropagatingItToTheAuthoritativeWrite() {
        StringRedisTemplate redisTemplate =
                mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException(
                        "test Redis failure"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RedisMailInspectionJobEventPublisherImpl publisher =
                new RedisMailInspectionJobEventPublisherImpl(
                        redisTemplate,
                        new RedisKeyFactory("test"),
                        new ObjectMapper(),
                        meterRegistry);

        publisher.publish(new MailInspectionJobEvent(
                MailInspectionJobEvent.SCHEMA_VERSION,
                "H".repeat(43),
                1L,
                MailInspectionJobEventType.STATUS,
                MailInspectionType.OPENAI_STATUS,
                Instant.parse("2026-07-29T10:00:00Z")));

        assertThat(meterRegistry.counter(
                "admin.mail.inspection.redis.pubsub.publish.failures")
                .count()).isEqualTo(1D);
    }
}
