package com.example.temperate.service.admin.mailinspection.recovery.impl;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoveryConnectionFactory;
import com.example.temperate.service.admin.mailinspection.recovery.MailInspectionRecoverySession;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 直接通过 Rabbit Java Client 创建物理连接，避免恢复 Channel 被 Spring 缓存后遗留永久 Unacked。
 */
@Component
@ConditionalOnProperty(
        prefix = "app.admin.mail-inspection.rabbit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class MailInspectionRecoveryConnectionFactoryImpl
        implements MailInspectionRecoveryConnectionFactory {

    private final AbstractConnectionFactory springConnectionFactory;

    public MailInspectionRecoveryConnectionFactoryImpl(
            AbstractConnectionFactory springConnectionFactory) {
        this.springConnectionFactory =
                Objects.requireNonNull(springConnectionFactory);
    }

    @Override
    public MailInspectionRecoverySession open(
            MailInspectionType type,
            String purpose) throws IOException, TimeoutException {
        String connectionName = "admin-mail-recovery-"
                + type.name().toLowerCase(Locale.ROOT).replace('_', '-')
                + "-"
                + sanitizePurpose(purpose);
        Connection connection = springConnectionFactory
                .getRabbitConnectionFactory()
                .newConnection(connectionName);
        try {
            Channel channel = connection.createChannel();
            if (channel == null) {
                throw new IOException(
                        "Rabbit recovery channel could not be created");
            }
            return new MailInspectionRecoverySession(connection, channel);
        } catch (IOException | RuntimeException exception) {
            connection.abort();
            throw exception;
        }
    }

    private static String sanitizePurpose(String purpose) {
        String value = Objects.requireNonNullElse(purpose, "scan")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-");
        return value.isBlank() ? "scan" : value;
    }
}
