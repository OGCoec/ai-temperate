package com.example.temperate.service.user.membership.payment.rabbit.impl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipClosingCheckPublisher;
import com.example.temperate.service.user.membership.payment.rabbit.MembershipPaymentCheckPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来锁定新订单只调度配置中的最终阶段，并保证已经到达的业务边界使用零延迟立即投递。
 */
final class MembershipPaymentFinalCheckSchedulerImplTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    void schedulesOnlyConfiguredFinalStagesAtTheirRealBoundaries() {
        MembershipPaymentCheckPublisher paymentPublisher =
                mock(MembershipPaymentCheckPublisher.class);
        MembershipClosingCheckPublisher closingPublisher =
                mock(MembershipClosingCheckPublisher.class);
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        MembershipPaymentProperties.Rabbit rabbit = new MembershipPaymentProperties.Rabbit(
                List.of(10L, 10L, 10L, 15L, 15L, 30L, 30L, 60L, 120L),
                List.of(30L, 30L, 60L, 60L, 120L),
                Duration.ofSeconds(30),
                3);
        when(properties.rabbit()).thenReturn(rabbit);
        MembershipPaymentFinalCheckSchedulerImpl scheduler =
                new MembershipPaymentFinalCheckSchedulerImpl(
                        paymentPublisher,
                        closingPublisher,
                        properties,
                        Clock.fixed(NOW, ZoneOffset.UTC));

        scheduler.schedulePending(
                "AaAjECcaAQGqi_h2Rl1PiA",
                OffsetDateTime.ofInstant(NOW.plusSeconds(5), ZoneOffset.UTC));
        scheduler.scheduleClosing(
                "AaAjECcaAQGqi_h2Rl1PiA",
                OffsetDateTime.ofInstant(NOW.minusMillis(1), ZoneOffset.UTC),
                2);

        verify(paymentPublisher).publishNext(
                "AaAjECcaAQGqi_h2Rl1PiA", 8, Duration.ofSeconds(5));
        verify(closingPublisher).publishNext(
                "AaAjECcaAQGqi_h2Rl1PiA", 4, 2, Duration.ZERO);
    }
}
