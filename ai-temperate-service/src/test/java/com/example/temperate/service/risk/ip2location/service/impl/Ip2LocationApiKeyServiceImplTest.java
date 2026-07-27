package com.example.temperate.service.risk.ip2location.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.risk.ip2location.domain.Ip2LocationImportMode;
import com.example.temperate.service.risk.ip2location.domain.Ip2LocationPlanType;
import com.example.temperate.service.risk.ip2location.domain.ProtectedIp2LocationKey;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchCommand;
import com.example.temperate.service.risk.ip2location.dto.Ip2LocationKeyBatchResult;
import com.example.temperate.service.risk.ip2location.security.Ip2LocationApiKeyProtector;
import com.example.temperate.service.risk.ip2location.store.Ip2LocationApiKeyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证管理员 API Key 批量导入由服务端计算套餐有效期，并在整体校验和确定性去重后进行一次有界 Redis 原子写入。
 */
class Ip2LocationApiKeyServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Test
    void duplicateInputIsProtectedOnceAndReportedWithoutEchoingKey() {
        Ip2LocationApiKeyStore store = mock(Ip2LocationApiKeyStore.class);
        when(store.writeBatch(
                        anyList(),
                        eq(50_000L),
                        eq(Ip2LocationImportMode.CREATE_ONLY)))
                .thenReturn(new Ip2LocationApiKeyStore.BatchWriteResult(1, 0, 0));
        Ip2LocationApiKeyServiceImpl service = service(store);

        Ip2LocationKeyBatchResult result = service.importBatch(
                new Ip2LocationKeyBatchCommand(
                        Ip2LocationPlanType.FREE,
                        50_000,
                        Ip2LocationImportMode.CREATE_ONLY,
                        List.of("duplicate-test-key", " duplicate-test-key ")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtectedIp2LocationKey>> keys =
                ArgumentCaptor.forClass(List.class);
        verify(store).writeBatch(
                keys.capture(),
                eq(50_000L),
                eq(Ip2LocationImportMode.CREATE_ONLY));
        assertThat(keys.getValue()).hasSize(1);
        assertThat(keys.getValue().getFirst().expiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.duplicateCount()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("duplicate-test-key");
    }

    @Test
    void oversizedBatchFailsBeforeRedisWrite() {
        Ip2LocationApiKeyStore store = mock(Ip2LocationApiKeyStore.class);
        Ip2LocationApiKeyServiceImpl service = service(store);
        List<String> values = Collections.nCopies(501, "valid-test-key");
        Ip2LocationKeyBatchCommand command = new Ip2LocationKeyBatchCommand(
                Ip2LocationPlanType.FREE,
                50_000,
                Ip2LocationImportMode.CREATE_ONLY,
                values);

        assertThatThrownBy(() -> service.importBatch(command))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    @Test
    void paidPlanExpiresOneCalendarMonthAfterServerTime() {
        Ip2LocationApiKeyStore store = mock(Ip2LocationApiKeyStore.class);
        when(store.writeBatch(
                        anyList(),
                        eq(50_000L),
                        eq(Ip2LocationImportMode.CREATE_ONLY)))
                .thenReturn(new Ip2LocationApiKeyStore.BatchWriteResult(1, 0, 0));
        Ip2LocationApiKeyServiceImpl service = service(store);

        service.importBatch(new Ip2LocationKeyBatchCommand(
                Ip2LocationPlanType.STARTER,
                50_000,
                Ip2LocationImportMode.CREATE_ONLY,
                List.of("starter-plan-test-key")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProtectedIp2LocationKey>> keys =
                ArgumentCaptor.forClass(List.class);
        verify(store).writeBatch(
                keys.capture(),
                eq(50_000L),
                eq(Ip2LocationImportMode.CREATE_ONLY));
        assertThat(keys.getValue().getFirst().expiresAt())
                .isEqualTo(Instant.parse("2026-08-25T10:00:00Z"));
    }

    @Test
    void missingPlanFailsBeforeRedisWrite() {
        Ip2LocationApiKeyStore store = mock(Ip2LocationApiKeyStore.class);
        Ip2LocationApiKeyServiceImpl service = service(store);

        Ip2LocationKeyBatchCommand command = new Ip2LocationKeyBatchCommand(
                null,
                50_000,
                Ip2LocationImportMode.CREATE_ONLY,
                List.of("missing-plan-test-key"));

        assertThatThrownBy(() -> service.importBatch(command))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    private static Ip2LocationApiKeyServiceImpl service(
            Ip2LocationApiKeyStore store) {
        String secret = Base64.getEncoder().encodeToString(
                "ip2location-service-test-secret-012345".getBytes());
        Ip2LocationApiKeyProtector protector = new Ip2LocationApiKeyProtector(
                secret,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        return new Ip2LocationApiKeyServiceImpl(
                store,
                protector,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
