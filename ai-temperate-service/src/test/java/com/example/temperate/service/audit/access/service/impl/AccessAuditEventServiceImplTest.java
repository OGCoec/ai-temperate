package com.example.temperate.service.audit.access.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.model.audit.access.AccessRequestAuditMessage;
import com.example.temperate.service.audit.access.command.AccessAuditCommand;
import com.example.temperate.service.audit.access.component.AccessAuditIpProtector;
import com.example.temperate.service.audit.access.publisher.AccessAuditPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证访问审计在发布边界前完成 IP 脱敏，并且发布失败不会传播到原业务请求。
 */
class AccessAuditEventServiceImplTest {

    @Test
    void publishesOnlyTheProtectedIpRepresentation() {
        AccessAuditPublisher publisher = mock(AccessAuditPublisher.class);
        AccessAuditEventServiceImpl service = service(publisher);

        service.record(command("203.0.113.77"));

        ArgumentCaptor<AccessRequestAuditMessage> captor =
                ArgumentCaptor.forClass(AccessRequestAuditMessage.class);
        verify(publisher).publish(captor.capture());
        AccessRequestAuditMessage message = captor.getValue();
        assertThat(message.payload().ipPrefix()).isEqualTo("203.0.113.0/24");
        assertThat(message.payload().ipHmac()).hasSize(43);
        assertThat(message.toString()).doesNotContain("203.0.113.77");
    }

    @Test
    void publisherFailureDoesNotEscapeToTheBusinessRequest() {
        AccessAuditPublisher publisher = mock(AccessAuditPublisher.class);
        doThrow(new IllegalStateException("broker unavailable"))
                .when(publisher).publish(any());
        AccessAuditEventServiceImpl service = service(publisher);

        assertThatCode(() -> service.record(command("203.0.113.77")))
                .doesNotThrowAnyException();
    }

    private static AccessAuditEventServiceImpl service(AccessAuditPublisher publisher) {
        return new AccessAuditEventServiceImpl(
                new AccessAuditIpProtector(
                        "0123456789abcdef0123456789abcdef"
                                .getBytes(StandardCharsets.UTF_8)),
                publisher,
                new SimpleMeterRegistry());
    }

    private static AccessAuditCommand command(String ip) {
        return new AccessAuditCommand(
                Instant.parse("2026-07-16T10:00:00Z"),
                UUID.randomUUID(),
                10001L,
                "GET",
                "/api/users/me",
                200,
                12L,
                "H5",
                ip);
    }
}
