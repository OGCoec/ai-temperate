package com.example.temperate.service.user.apikey.usage.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.PublicIdCodec;
import com.example.temperate.mapper.ai.ApiKeyUsageQueryMapper;
import com.example.temperate.mapper.ai.UserApiKeyMapper;
import com.example.temperate.model.ai.entity.ApiKeyUsageRequestRow;
import com.example.temperate.model.ai.entity.ApiKeyUsageSummaryRow;
import com.example.temperate.model.ai.entity.UserApiKey;
import com.example.temperate.model.ai.enums.AiModelBillingStatus;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementErrorCode;
import com.example.temperate.service.user.apikey.management.ApiKeyManagementException;
import com.example.temperate.service.user.apikey.usage.ApiKeyUsageCursorCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 该测试是来锁定 API Key 调用明细的所有权隐藏、服务器时间窗口、字符串精度和稳定游标分页边界。
 */
final class ApiKeyUsageQueryServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-18T16:00:00Z");
    private static final byte[] API_KEY_ID = hybridId(7);
    private static final byte[] DIGEST = new byte[32];

    private UserApiKeyMapper keyMapper;
    private ApiKeyUsageQueryMapper usageMapper;
    private ApiKeyUsageQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        keyMapper = mock(UserApiKeyMapper.class);
        usageMapper = mock(ApiKeyUsageQueryMapper.class);
        service = new ApiKeyUsageQueryServiceImpl(
                keyMapper,
                usageMapper,
                new PublicIdCodec(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defaultsToThePreviousServerHourAndReturnsDecimalStrings() {
        UserApiKey key = ownedKey(1);
        when(keyMapper.findOwnedById(API_KEY_ID, 99L)).thenReturn(key);
        when(usageMapper.summarize(same(DIGEST), any(), any()))
                .thenReturn(summary(42L, 125_000L, 48_000L, 18_500L, 386L, 1L, 20L));
        when(usageMapper.findPage(same(DIGEST), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(request(23L, AiModelBillingStatus.SETTLED, 3_150L, 1_200L, 480L, 12L)));

        var page = service.query(99L, API_KEY_ID, null, null, null, 20);

        assertThat(page.period().from()).isEqualTo("2026-08-18T15:00:00Z");
        assertThat(page.period().to()).isEqualTo("2026-08-18T16:00:00Z");
        assertThat(page.summary().requestCount()).isEqualTo("42");
        assertThat(page.summary().uncachedPromptTokens()).isEqualTo("77000");
        assertThat(page.items().getFirst().uncachedPromptTokens()).isEqualTo("1950");
        assertThat(page.items().getFirst().chargedQuotaMinor()).isEqualTo("12");
        assertThat(page.nextCursor()).isNull();
        verify(usageMapper).findPage(
                same(DIGEST),
                any(OffsetDateTime.class),
                any(OffsetDateTime.class),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(21));
    }

    @Test
    void reservedRequestHasNoActualChargeAndUsesReservedAmount() {
        when(keyMapper.findOwnedById(API_KEY_ID, 99L)).thenReturn(ownedKey(0));
        when(usageMapper.summarize(any(), any(), any()))
                .thenReturn(summary(1L, 0L, 0L, 0L, 0L, 1L, 15L));
        ApiKeyUsageRequestRow row = request(
                24L, AiModelBillingStatus.RESERVED, null, null, null, null);
        row.setReservedQuotaMinor(15L);
        when(usageMapper.findPage(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(row));

        var item = service.query(99L, API_KEY_ID, null, null, null, 20)
                .items().getFirst();

        assertThat(item.chargedQuotaMinor()).isNull();
        assertThat(item.reservedQuotaMinor()).isEqualTo("15");
        assertThat(item.billingStatus()).isEqualTo("RESERVED");
    }

    @Test
    void refundedChargeIsZeroWhileReconcileKeepsCurrentCharge() {
        when(keyMapper.findOwnedById(API_KEY_ID, 99L)).thenReturn(ownedKey(1));
        when(usageMapper.summarize(any(), any(), any()))
                .thenReturn(summary(2L, 10L, 0L, 5L, 19L, 0L, 0L));
        when(usageMapper.findPage(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        request(25L, AiModelBillingStatus.FAILED_REFUNDED, 5L, 0L, 2L, 99L),
                        request(24L, AiModelBillingStatus.RECONCILE_REQUIRED, 5L, 0L, 3L, 19L)));

        var items = service.query(99L, API_KEY_ID, null, null, null, 20).items();

        assertThat(items.get(0).chargedQuotaMinor()).isEqualTo("0");
        assertThat(items.get(1).chargedQuotaMinor()).isEqualTo("19");
        assertThat(items.get(1).billingStatus()).isEqualTo("RECONCILE_REQUIRED");
    }

    @Test
    void hidesDeletedAndForeignKeysAsNotFound() {
        UserApiKey deleted = ownedKey(2);
        when(keyMapper.findOwnedById(API_KEY_ID, 99L)).thenReturn(deleted);

        assertThatThrownBy(() -> service.query(
                99L, API_KEY_ID, null, null, null, 20))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(error -> ((ApiKeyManagementException) error).code())
                .isEqualTo(ApiKeyManagementErrorCode.API_KEY_NOT_FOUND);

        when(keyMapper.findOwnedById(API_KEY_ID, 99L)).thenReturn(null);
        assertThatThrownBy(() -> service.query(
                99L, API_KEY_ID, null, null, null, 20))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(error -> ((ApiKeyManagementException) error).code())
                .isEqualTo(ApiKeyManagementErrorCode.API_KEY_NOT_FOUND);
    }

    @Test
    void generatesNextCursorFromTheLastReturnedRow() {
        when(keyMapper.findOwnedById(API_KEY_ID, 99L)).thenReturn(ownedKey(1));
        when(usageMapper.summarize(any(), any(), any()))
                .thenReturn(summary(2L, 2L, 0L, 2L, 2L, 0L, 0L));
        ApiKeyUsageRequestRow first = request(
                25L, AiModelBillingStatus.SETTLED, 1L, 0L, 1L, 1L);
        ApiKeyUsageRequestRow extra = request(
                24L, AiModelBillingStatus.SETTLED, 1L, 0L, 1L, 1L);
        extra.setCreatedAt(OffsetDateTime.parse("2026-08-18T15:41:10Z"));
        when(usageMapper.findPage(any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(first, extra));

        var page = service.query(99L, API_KEY_ID, null, null, null, 1);

        assertThat(page.items()).hasSize(1);
        var cursor = new ApiKeyUsageCursorCodec().decode(page.nextCursor());
        assertThat(cursor.createdAt()).isEqualTo(first.getCreatedAt());
        assertThat(cursor.usageId()).containsExactly(first.getUsageId());
    }

    @Test
    void rejectsIncompleteFutureAndOversizedRanges() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);

        assertInvalid(() -> service.query(99L, API_KEY_ID, now.minusHours(1), null, null, 20));
        assertInvalid(() -> service.query(99L, API_KEY_ID, now.minusHours(1), now.plusNanos(1), null, 20));
        assertInvalid(() -> service.query(99L, API_KEY_ID, now.minusDays(32), now, null, 20));
        assertInvalid(() -> service.query(99L, API_KEY_ID, now.minusHours(1), now, null, 101));
    }

    @Test
    void convertsDatabaseFailureToStableUnavailableError() {
        when(keyMapper.findOwnedById(API_KEY_ID, 99L))
                .thenThrow(new DataAccessResourceFailureException("database details"));

        assertThatThrownBy(() -> service.query(
                99L, API_KEY_ID, null, null, null, 20))
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(error -> ((ApiKeyManagementException) error).code())
                .isEqualTo(ApiKeyManagementErrorCode.USAGE_QUERY_UNAVAILABLE);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(ApiKeyManagementException.class)
                .extracting(error -> ((ApiKeyManagementException) error).code())
                .isEqualTo(ApiKeyManagementErrorCode.INPUT_INVALID);
    }

    private static UserApiKey ownedKey(int status) {
        UserApiKey key = new UserApiKey();
        key.setId(API_KEY_ID.clone());
        key.setLoginIdentityId(99L);
        key.setKeyDigest(DIGEST);
        key.setStatus(status);
        return key;
    }

    private static ApiKeyUsageSummaryRow summary(
            long count,
            long prompt,
            long cached,
            long completion,
            long charged,
            long pending,
            long reserved) {
        ApiKeyUsageSummaryRow row = new ApiKeyUsageSummaryRow();
        row.setRequestCount(count);
        row.setPromptTokens(prompt);
        row.setCachedPromptTokens(cached);
        row.setCompletionTokens(completion);
        row.setChargedQuotaMinor(charged);
        row.setPendingRequestCount(pending);
        row.setPendingReservedQuotaMinor(reserved);
        return row;
    }

    private static ApiKeyUsageRequestRow request(
            long id,
            AiModelBillingStatus status,
            Long prompt,
            Long cached,
            Long completion,
            Long charged) {
        ApiKeyUsageRequestRow row = new ApiKeyUsageRequestRow();
        row.setUsageId(hybridId((int) id));
        row.setAiModelId(23L);
        row.setModelName("gpt-test");
        row.setVendor("openai");
        row.setStream(true);
        row.setBillingStatus(status.code());
        row.setPromptTokens(prompt);
        row.setCachedPromptTokens(cached);
        row.setCompletionTokens(completion);
        row.setChargedQuotaMinor(charged);
        row.setReservedQuotaMinor(15L);
        row.setFinishReason("STOP");
        row.setCreatedAt(OffsetDateTime.parse("2026-08-18T15:42:10Z"));
        row.setSettledAt(status == AiModelBillingStatus.RESERVED
                ? null
                : OffsetDateTime.parse("2026-08-18T15:42:14Z"));
        return row;
    }

    private static byte[] hybridId(int suffix) {
        byte[] id = new byte[16];
        id[15] = (byte) suffix;
        return id;
    }
}
