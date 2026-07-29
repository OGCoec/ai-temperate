package com.example.temperate.service.admin.mailinspection.recovery;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 封装 Rabbit 原生物理 Connection 与 Channel，确保关闭时不会被 Spring 缓存并保留 Unacked。
 */
public final class MailInspectionRecoverySession implements AutoCloseable {

    private final Connection connection;
    private final Channel channel;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MailInspectionRecoverySession(
            Connection connection,
            Channel channel) {
        this.connection = Objects.requireNonNull(connection);
        this.channel = Objects.requireNonNull(channel);
    }

    public Channel channel() {
        return channel;
    }

    /**
     * NACK 失败时强制终止物理连接，Rabbit 会把该连接尚未确认的消息重新放回 Ready。
     */
    public void forceClose() {
        if (closed.compareAndSet(false, true)) {
            try {
                connection.abort();
            } catch (RuntimeException ignored) {
                // 强制关闭是 NACK 失败后的最后保障；关闭异常不能覆盖最初的恢复失败。
            }
        }
    }

    @Override
    public void close() throws IOException, TimeoutException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException ioFailure = null;
        TimeoutException timeoutFailure = null;
        try {
            if (channel.isOpen()) {
                channel.close();
            }
        } catch (IOException exception) {
            ioFailure = exception;
        } catch (TimeoutException exception) {
            timeoutFailure = exception;
        } finally {
            if (connection.isOpen()) {
                connection.close();
            }
        }
        if (ioFailure != null) {
            throw ioFailure;
        }
        if (timeoutFailure != null) {
            throw timeoutFailure;
        }
    }
}
