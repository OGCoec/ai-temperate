package com.example.temperate.service.user.aiconversation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.example.temperate.service.user.aiconversation.config.AiConversationAsyncGenerationProperties;
import com.example.temperate.service.user.aiconversation.generation.rabbit.impl.RabbitAiConversationGenerationEventPublisherImpl;
import com.example.temperate.service.user.aiconversation.observability.AiConversationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 验证 Generation 发布器固定使用持久消息、业务 messageId、Confirm 和三十秒延迟头。
 */
class RabbitAiConversationGenerationEventPublisherTest {

    @Test
    void immediateAndDelayedMessagesArePersistentAndConfirmed() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        List<Message> sent = new ArrayList<>();
        doAnswer(invocation -> {
            MessagePostProcessor processor = invocation.getArgument(3);
            CorrelationData correlation = invocation.getArgument(4);
            Message message = processor.postProcessMessage(
                    new Message(new byte[0], new MessageProperties()));
            sent.add(message);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).convertAndSend(
                anyString(), anyString(), any(), any(MessagePostProcessor.class),
                any(CorrelationData.class));
        var publisher = new RabbitAiConversationGenerationEventPublisherImpl(
                template,
                properties(),
                new AiConversationMetrics(new SimpleMeterRegistry()),
                Clock.fixed(Instant.parse("2026-08-01T16:00:00Z"), ZoneOffset.UTC));

        publisher.publishGenerationRequested(
                "AZ-vpV3kfag70-0EMMUETQ",
                "AZ-vpV3kfag70-0EMMUETg",
                "trace-safe");
        publisher.publishDetachCheck(
                "AZ-vpV3kfag70-0EMMUETQ",
                3L,
                OffsetDateTime.parse("2026-08-01T16:00:00Z"),
                "trace-safe");

        assertThat(sent).hasSize(2);
        assertThat(sent).allSatisfy(message -> {
            assertThat(message.getMessageProperties().getDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
        });
        Object immediateDelay = sent.get(0).getMessageProperties().getHeader("x-delay");
        Object delayed = sent.get(1).getMessageProperties().getHeader("x-delay");
        assertThat(immediateDelay).isNull();
        assertThat(delayed).isEqualTo(30_000L);
    }

    private static AiConversationAsyncGenerationProperties properties() {
        return new AiConversationAsyncGenerationProperties(
                true,
                "instance-test",
                Duration.ofSeconds(30),
                Duration.ofSeconds(1),
                Duration.ofHours(24),
                2,
                Duration.ofMinutes(15));
    }
}
