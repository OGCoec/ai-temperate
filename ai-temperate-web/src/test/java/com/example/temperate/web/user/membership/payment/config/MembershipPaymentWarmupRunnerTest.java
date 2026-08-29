package com.example.temperate.web.user.membership.payment.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentWarmupProperties;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentRabbitNames;
import com.example.temperate.service.user.membership.payment.warmup.MembershipPaymentInfrastructureWarmupService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.ApplicationArguments;

/**
 * 该单元测试是来锁定启动预热的开关、失败策略和 Rabbit 被动声明边界，禁止发送任何业务消息。
 */
final class MembershipPaymentWarmupRunnerTest {

    @Test
    void disabledWarmupHasNoInfrastructureInteraction() {
        MembershipPaymentInfrastructureWarmupService warmupService =
                mock(MembershipPaymentInfrastructureWarmupService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        MembershipPaymentWarmupRunner runner = new MembershipPaymentWarmupRunner(
                new MembershipPaymentWarmupProperties(false, true),
                warmupService,
                rabbitTemplate);

        runner.run(mock(ApplicationArguments.class));

        verifyNoInteractions(warmupService, rabbitTemplate);
    }

    @Test
    void enabledWarmupLoadsRedisBeforePassiveRabbitDeclarations() throws Exception {
        MembershipPaymentInfrastructureWarmupService warmupService =
                mock(MembershipPaymentInfrastructureWarmupService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Channel channel = mock(Channel.class);
        doAnswer(invocation -> ((ChannelCallback<?>) invocation.getArgument(0))
                        .doInRabbit(channel))
                .when(rabbitTemplate)
                .execute(any(ChannelCallback.class));
        MembershipPaymentWarmupRunner runner = new MembershipPaymentWarmupRunner(
                new MembershipPaymentWarmupProperties(true, true),
                warmupService,
                rabbitTemplate);

        runner.run(mock(ApplicationArguments.class));

        InOrder ordered = inOrder(warmupService, rabbitTemplate);
        ordered.verify(warmupService).warmUpRedisInfrastructure();
        ordered.verify(rabbitTemplate).execute(any(ChannelCallback.class));
        verify(channel).exchangeDeclarePassive(MembershipPaymentRabbitNames.PAYMENT_EXCHANGE);
        verify(channel).exchangeDeclarePassive(MembershipPaymentRabbitNames.CLOSING_EXCHANGE);
        verify(channel).queueDeclarePassive(MembershipPaymentRabbitNames.PAYMENT_QUEUE);
        verify(channel).queueDeclarePassive(MembershipPaymentRabbitNames.CLOSING_QUEUE);
    }

    @Test
    void failFastPropagatesRedisWarmupFailureBeforeRabbitAccess() {
        MembershipPaymentInfrastructureWarmupService warmupService =
                mock(MembershipPaymentInfrastructureWarmupService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RuntimeException failure = new IllegalStateException("redis unavailable");
        org.mockito.Mockito.doThrow(failure)
                .when(warmupService)
                .warmUpRedisInfrastructure();
        MembershipPaymentWarmupRunner runner = new MembershipPaymentWarmupRunner(
                new MembershipPaymentWarmupProperties(true, true),
                warmupService,
                rabbitTemplate);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isSameAs(failure);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void nonFailFastContainsWarmupFailureAndSkipsRabbitAccess() {
        MembershipPaymentInfrastructureWarmupService warmupService =
                mock(MembershipPaymentInfrastructureWarmupService.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("redis unavailable"))
                .when(warmupService)
                .warmUpRedisInfrastructure();
        MembershipPaymentWarmupRunner runner = new MembershipPaymentWarmupRunner(
                new MembershipPaymentWarmupProperties(true, false),
                warmupService,
                rabbitTemplate);

        assertThatCode(() -> runner.run(mock(ApplicationArguments.class)))
                .doesNotThrowAnyException();
        verifyNoInteractions(rabbitTemplate);
    }
}
