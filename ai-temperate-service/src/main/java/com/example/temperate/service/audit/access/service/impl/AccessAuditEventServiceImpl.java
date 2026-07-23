package com.example.temperate.service.audit.access.service.impl;

import com.example.temperate.model.audit.access.AccessRequestAuditMessage;
import com.example.temperate.model.audit.access.AccessRequestAuditPayload;
import com.example.temperate.service.audit.access.command.AccessAuditCommand;
import com.example.temperate.service.audit.access.component.AccessAuditIpProtector;
import com.example.temperate.service.audit.access.domain.ProtectedClientIp;
import com.example.temperate.service.audit.access.publisher.AccessAuditPublisher;
import com.example.temperate.service.audit.access.service.AccessAuditEventService;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 将请求完成事实转换为不含原始 IP 的版本化消息，并以失败开放方式交给异步发布边界。
 */
@Service
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class AccessAuditEventServiceImpl implements AccessAuditEventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccessAuditEventServiceImpl.class);

    private final AccessAuditIpProtector ipProtector;
    private final AccessAuditPublisher publisher;
    private final MeterRegistry meterRegistry;

    public AccessAuditEventServiceImpl(
            AccessAuditIpProtector ipProtector,
            AccessAuditPublisher publisher,
            MeterRegistry meterRegistry) {
        this.ipProtector = ipProtector;
        this.publisher = publisher;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void record(AccessAuditCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        try {
            // 原始 IP 在本方法内立即转换，跨越 Publisher 边界的对象只包含前缀和 HMAC。
            ProtectedClientIp protectedIp = ipProtector.protect(command.canonicalClientIp());
            AccessRequestAuditPayload payload = new AccessRequestAuditPayload(
                    command.userId(),
                    command.method(),
                    command.routeTemplate(),
                    command.statusCode(),
                    command.durationMillis(),
                    command.clientPlatform(),
                    protectedIp.ipFamily(),
                    protectedIp.ipPrefix(),
                    protectedIp.ipHmac());
            AccessRequestAuditMessage message = new AccessRequestAuditMessage(
                    UUID.randomUUID(),
                    AccessRequestAuditMessage.EVENT_TYPE,
                    AccessRequestAuditMessage.SCHEMA_VERSION,
                    command.occurredAt(),
                    command.traceId(),
                    payload);
            publisher.publish(message);
            counter("submitted");
        } catch (RuntimeException exception) {
            // 审计属于旁路安全记录，发布故障不得把成功业务请求改写为失败响应。
            counter("error");
            LOGGER.warn("访问审计事件发布失败，原业务响应保持不变，traceId={}", command.traceId());
        }
    }

    private void counter(String outcome) {
        meterRegistry.counter("auth.access_audit.publish", "outcome", outcome).increment();
    }
}
