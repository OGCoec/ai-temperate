package com.example.temperate.service.admin.mailinspection.recovery.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;

/**
 * 验证恢复扫描绕过 Spring Channel Cache，并在会话结束时关闭真实 Rabbit Channel 与 Connection。
 */
final class MailInspectionRecoveryConnectionFactoryTest {

    @Test
    void opensAndClosesNativePhysicalConnection() throws Exception {
        AbstractConnectionFactory springFactory =
                mock(AbstractConnectionFactory.class);
        com.rabbitmq.client.ConnectionFactory rabbitFactory =
                mock(com.rabbitmq.client.ConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Channel channel = mock(Channel.class);
        when(springFactory.getRabbitConnectionFactory())
                .thenReturn(rabbitFactory);
        when(rabbitFactory.newConnection(
                contains("admin-mail-recovery-openai-status")))
                .thenReturn(connection);
        when(connection.createChannel()).thenReturn(channel);
        when(connection.isOpen()).thenReturn(true);
        when(channel.isOpen()).thenReturn(true);

        MailInspectionRecoveryConnectionFactoryImpl factory =
                new MailInspectionRecoveryConnectionFactoryImpl(
                        springFactory);

        try (MailInspectionRecoverySession session = factory.open(
                MailInspectionType.OPENAI_STATUS,
                "startup")) {
            assertThat(session.channel()).isSameAs(channel);
        }

        verify(channel).close();
        verify(connection).close();
    }
}
