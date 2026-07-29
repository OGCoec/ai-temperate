package com.example.temperate.service.admin.mailinspection.event.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 将邮件任务变更作为轻量 Redis Pub/Sub 通知发布，并在失败时仅记录可观测故障。
 */
@Component
public final class RedisMailInspectionJobEventPublisherImpl
        implements MailInspectionJobEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            RedisMailInspectionJobEventPublisherImpl.class);

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;
    private final Counter publishFailures;

    public RedisMailInspectionJobEventPublisherImpl(
            StringRedisTemplate redisTemplate,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.publishFailures = Counter.builder(
                        "admin.mail.inspection.redis.pubsub.publish.failures")
                .description(
                        "Redis Pub/Sub mail inspection wake-up publish failures")
                .register(Objects.requireNonNull(meterRegistry));
    }

    @Override
    public void publish(MailInspectionJobEvent event) {
        try {
            redisTemplate.convertAndSend(
                    keyFactory.adminMailInspectionJobEventsChannel(),
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException | RuntimeException exception) {
            // Pub/Sub 只负责唤醒；权威状态已经落入 Redis，失败后由 SSE 心跳比较 revision 修复。
            publishFailures.increment();
            LOGGER.warn(
                    "event={} jobRef={} revision={} exceptionType={}",
                    "admin_mail_inspection_event_publish_failed",
                    event.jobHash().substring(0, Math.min(16, event.jobHash().length())),
                    event.revision(),
                    exception.getClass().getName());
        }
    }
}
