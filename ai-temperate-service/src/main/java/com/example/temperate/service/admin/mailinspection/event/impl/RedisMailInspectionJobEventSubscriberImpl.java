package com.example.temperate.service.admin.mailinspection.event.impl;

import com.example.temperate.common.redis.key.RedisKeyFactory;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEventSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * 订阅 Redis 邮件任务通知频道并安全扇出到本实例 SSE 监听器，丢失通知由 revision 校准弥补。
 */
@Component
public final class RedisMailInspectionJobEventSubscriberImpl
        implements MailInspectionJobEventSubscriber, MessageListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            RedisMailInspectionJobEventSubscriberImpl.class);

    private final RedisMessageListenerContainer container;
    private final RedisKeyFactory keyFactory;
    private final ObjectMapper objectMapper;
    private final CopyOnWriteArrayList<Consumer<MailInspectionJobEvent>>
            listeners = new CopyOnWriteArrayList<>();

    public RedisMailInspectionJobEventSubscriberImpl(
            RedisMessageListenerContainer container,
            RedisKeyFactory keyFactory,
            ObjectMapper objectMapper) {
        this.container = Objects.requireNonNull(container);
        this.keyFactory = Objects.requireNonNull(keyFactory);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @PostConstruct
    void registerTopic() {
        container.addMessageListener(
                this,
                new ChannelTopic(
                        keyFactory.adminMailInspectionJobEventsChannel()));
    }

    @Override
    public AutoCloseable subscribe(Consumer<MailInspectionJobEvent> listener) {
        Consumer<MailInspectionJobEvent> safe =
                Objects.requireNonNull(listener);
        listeners.add(safe);
        return () -> listeners.remove(safe);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MailInspectionJobEvent event = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    MailInspectionJobEvent.class);
            listeners.forEach(listener -> listener.accept(event));
        } catch (RuntimeException | java.io.IOException exception) {
            LOGGER.warn(
                    "event={} exceptionType={}",
                    "admin_mail_inspection_event_subscribe_failed",
                    exception.getClass().getName());
        }
    }
}
