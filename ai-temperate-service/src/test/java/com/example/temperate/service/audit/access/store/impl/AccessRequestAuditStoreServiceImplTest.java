package com.example.temperate.service.audit.access.store.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.temperate.mapper.audit.access.AccessRequestAuditMapper;
import com.example.temperate.model.audit.access.AccessRequestAuditRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证访问审计存储按批次执行单次 Mapper 调用，并拒绝超过配置边界的无界批量输入。
 */
class AccessRequestAuditStoreServiceImplTest {

    @Test
    void persistsAWholeBatchWithOneMapperCall() {
        AccessRequestAuditMapper mapper = mock(AccessRequestAuditMapper.class);
        AccessRequestAuditStoreServiceImpl service =
                new AccessRequestAuditStoreServiceImpl(mapper, 200);
        List<AccessRequestAuditRecord> records = List.of(record(), record());

        service.storeBatch(records);

        verify(mapper).insertBatch(records);
    }

    @Test
    void rejectsAnOversizedBatchBeforeDatabaseIo() {
        AccessRequestAuditMapper mapper = mock(AccessRequestAuditMapper.class);
        AccessRequestAuditStoreServiceImpl service =
                new AccessRequestAuditStoreServiceImpl(mapper, 1);
        List<AccessRequestAuditRecord> records = List.of(record(), record());

        assertThatThrownBy(() -> service.storeBatch(records))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertBatch(records);
    }

    @Test
    void appliesTheConfiguredTransactionDeadlineToTheDatabaseWrite() throws Exception {
        Transactional transactional = AccessRequestAuditStoreServiceImpl.class
                .getMethod("storeBatch", List.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.timeoutString())
                .isEqualTo("${app.access-audit.store-timeout-seconds:15}");
    }

    private static AccessRequestAuditRecord record() {
        return new AccessRequestAuditRecord(
                UUID.randomUUID(),
                10001L,
                UUID.randomUUID(),
                Instant.parse("2026-07-16T10:00:00Z"),
                "GET",
                "/api/users/me",
                200,
                12L,
                "H5",
                4,
                "203.0.113.0/24",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
    }
}
