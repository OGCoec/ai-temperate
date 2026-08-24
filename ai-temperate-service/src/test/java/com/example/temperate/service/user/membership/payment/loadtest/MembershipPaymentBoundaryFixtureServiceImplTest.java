package com.example.temperate.service.user.membership.payment.loadtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.identity.UserLoginIdentityMapper;
import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipPaymentCallbackMapper;
import com.example.temperate.mapper.user.profile.UserProfileMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.auth.enums.RegistrationSource;
import com.example.temperate.model.user.entity.UserLoginIdentity;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.entity.UserProfile;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.MembershipOrderEntitlementResolution;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.user.membership.MembershipQuotaPlan;
import com.example.temperate.service.user.membership.MembershipQuotaPlanService;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentBoundaryLoadtestProperties;
import com.example.temperate.service.user.membership.payment.loadtest.impl.MembershipPaymentBoundaryFixtureServiceImpl;
import com.example.temperate.service.user.profile.cache.UserProfileCacheInvalidationExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 该测试是来锁定四十万个持久边界账号只能在固定空区间内批量创建，并且每轮只清理清单内订单后恢复 FREE 基线。
 */
final class MembershipPaymentBoundaryFixtureServiceImplTest {

    private static final long FIRST_ID = 70_000_000_000_000_000L;
    private static final Instant NOW = Instant.parse("2026-08-23T17:00:00Z");

    private UserLoginIdentityMapper identityMapper;
    private UserProfileMapper profileMapper;
    private UserMembershipQuotaMapper quotaMapper;
    private MembershipOrderMapper orderMapper;
    private MembershipPaymentCallbackMapper callbackMapper;
    private MembershipQuotaPlanService planService;
    private UserProfileCacheInvalidationExecutor invalidationExecutor;

    @BeforeEach
    void setUp() {
        identityMapper = mock(UserLoginIdentityMapper.class);
        profileMapper = mock(UserProfileMapper.class);
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        orderMapper = mock(MembershipOrderMapper.class);
        callbackMapper = mock(MembershipPaymentCallbackMapper.class);
        planService = mock(MembershipQuotaPlanService.class);
        invalidationExecutor = mock(UserProfileCacheInvalidationExecutor.class);
        when(planService.getRequired(MembershipTier.FREE))
                .thenReturn(new MembershipQuotaPlan(5_000L, Duration.ofDays(7)));
    }

    @Test
    void createsAnEmptyFixedRangeInEightHundredBatchesAndRetainsInvalidEmails() {
        when(identityMapper.findByIds(anyList())).thenReturn(List.of());
        when(profileMapper.findByLoginIdentityIds(anyList())).thenReturn(List.of());
        when(quotaMapper.findByLoginIdentityIds(anyList())).thenReturn(List.of());
        when(identityMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(profileMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(quotaMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);

        MembershipPaymentBoundaryFixtureState result = service(true).prepare();

        assertThat(result.prepared()).isTrue();
        assertThat(result.identityCount()).isEqualTo(40_000);
        ArgumentCaptor<List<UserLoginIdentity>> batches = ArgumentCaptor.forClass(List.class);
        verify(identityMapper, times(80)).batchInsertBoundaryFixtures(batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSize(500));
        assertThat(batches.getAllValues().stream().flatMap(List::stream))
                .extracting(UserLoginIdentity::getEmail)
                .hasSize(40_000)
                .doesNotHaveDuplicates()
                .allMatch(email -> email.endsWith(".invalid"));
        verify(profileMapper, times(80)).batchInsertBoundaryFixtures(anyList());
        verify(quotaMapper, times(80)).batchInsertBoundaryFixtures(anyList());
        verifyInvalidationInEightyPages();
    }

    @Test
    void rejectsDisabledOrMismatchedPersistentTemplatesWithoutOverwriting() {
        assertThatThrownBy(() -> service(false).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        when(identityMapper.findByIds(anyList())).thenAnswer(invocation -> identities(invocation.getArgument(0)));
        when(profileMapper.findByLoginIdentityIds(anyList())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));
        when(quotaMapper.findByLoginIdentityIds(anyList())).thenAnswer(invocation -> quotas(invocation.getArgument(0)));
        List<UserLoginIdentity> wrong = identities(new MembershipPaymentBoundaryLoadtestPolicy().pageUserIds(0));
        wrong.getFirst().setEmail("foreign@example.invalid");
        when(identityMapper.findByIds(new MembershipPaymentBoundaryLoadtestPolicy().pageUserIds(0)))
                .thenReturn(wrong);

        assertThatThrownBy(() -> service(true).prepare())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("template");
        verify(identityMapper, never()).batchInsertBoundaryFixtures(anyList());
        verify(quotaMapper, never()).batchGrantPaidMemberships(anyString());
    }

    @Test
    void resetsOnlyExactTerminalRunOrdersAndKeepsAllTemplates() {
        stubExactTemplates();
        byte[] closedId = bytes((byte) 21);
        byte[] paidId = bytes((byte) 22);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 40_000L)).thenReturn(2, 0);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 40_000L)).thenReturn(0);
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(
                order(closedId, FIRST_ID, MembershipOrderStatus.CLOSED,
                        MembershipOrderEntitlementResolution.NOT_GRANTED),
                order(paidId, FIRST_ID + 1L, MembershipOrderStatus.PAID,
                        MembershipOrderEntitlementResolution.APPLIED)));
        when(callbackMapper.deleteByOrderIdsJson(anyString())).thenReturn(1);
        when(orderMapper.deleteByIdsJson(anyString())).thenReturn(2);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(500);

        MembershipPaymentBoundaryFixtureState result =
                service(true).reset(List.of(closedId, paidId));

        assertThat(result.prepared()).isTrue();
        verify(callbackMapper).deleteByOrderIdsJson(anyString());
        verify(orderMapper).deleteByIdsJson(anyString());
        verify(quotaMapper, times(80)).batchGrantPaidMemberships(anyString());
        verifyInvalidationInEightyPages();
        assertThat(identityMapper.findByIds(allIds().subList(0, 500))).hasSize(500);
    }

    @Test
    void refusesActiveOrUnresolvedOrdersBeforeDeletingAnything() {
        byte[] activeId = bytes((byte) 31);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 40_000L)).thenReturn(1);
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(
                order(activeId, FIRST_ID, MembershipOrderStatus.CLOSING, null)));

        assertThatThrownBy(() -> service(true).reset(List.of(activeId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
        verify(callbackMapper, never()).deleteByOrderIdsJson(anyString());
        verify(orderMapper, never()).deleteByIdsJson(anyString());
    }

    @Test
    void resetsLargeManifestInTwoThousandOrderSqlBatches() {
        stubExactTemplates();
        List<byte[]> ids = java.util.stream.IntStream.range(0, 2_001)
                .mapToObj(MembershipPaymentBoundaryFixtureServiceImplTest::indexedBytes)
                .toList();
        List<MembershipOrder> firstBatch = java.util.stream.IntStream.range(0, 2_000)
                .mapToObj(index -> order(
                        ids.get(index),
                        FIRST_ID + index,
                        MembershipOrderStatus.PAID,
                        MembershipOrderEntitlementResolution.APPLIED))
                .toList();
        List<MembershipOrder> secondBatch = List.of(order(
                ids.getLast(),
                FIRST_ID + 2_000L,
                MembershipOrderStatus.PAID,
                MembershipOrderEntitlementResolution.APPLIED));
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 40_000L))
                .thenReturn(2_001, 0);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 40_000L))
                .thenReturn(0);
        when(orderMapper.findByIdsJsonForUpdate(anyString()))
                .thenReturn(firstBatch, secondBatch);
        when(orderMapper.deleteByIdsJson(anyString())).thenReturn(2_000, 1);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(500);

        MembershipPaymentBoundaryFixtureState result = service(true).reset(ids);

        assertThat(result.prepared()).isTrue();
        verify(orderMapper, times(2)).findByIdsJsonForUpdate(anyString());
        verify(callbackMapper, times(2)).deleteByOrderIdsJson(anyString());
        verify(orderMapper, times(2)).deleteByIdsJson(anyString());
    }

    private MembershipPaymentBoundaryFixtureService service(boolean enabled) {
        return new MembershipPaymentBoundaryFixtureServiceImpl(
                new MembershipPaymentBoundaryLoadtestProperties(enabled),
                new MembershipPaymentBoundaryLoadtestPolicy(),
                identityMapper,
                profileMapper,
                quotaMapper,
                orderMapper,
                callbackMapper,
                planService,
                invalidationExecutor,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubExactTemplates() {
        when(identityMapper.findByIds(anyList())).thenAnswer(invocation -> identities(invocation.getArgument(0)));
        when(profileMapper.findByLoginIdentityIds(anyList())).thenAnswer(invocation -> profiles(invocation.getArgument(0)));
        when(quotaMapper.findByLoginIdentityIds(anyList())).thenAnswer(invocation -> quotas(invocation.getArgument(0)));
    }

    private static List<Long> allIds() {
        return java.util.stream.LongStream.range(FIRST_ID, FIRST_ID + 40_000L).boxed().toList();
    }

    private void verifyInvalidationInEightyPages() {
        ArgumentCaptor<List<Long>> batches = ArgumentCaptor.forClass(List.class);
        verify(invalidationExecutor, times(80)).evictAfterCommit(batches.capture());

        MembershipPaymentBoundaryLoadtestPolicy policy =
                new MembershipPaymentBoundaryLoadtestPolicy();
        assertThat(batches.getAllValues())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 80)
                        .mapToObj(policy::pageUserIds)
                        .toList())
                .allSatisfy(batch -> assertThat(batch).hasSize(500));
    }

    private static List<UserLoginIdentity> identities(List<Long> ids) {
        return ids.stream().map(id -> {
            UserLoginIdentity row = new UserLoginIdentity();
            row.setId(id);
            row.setRegistrationSource(RegistrationSource.STANDARD);
            row.setEmail("membership-boundary-%04d@example.invalid".formatted(id - FIRST_ID));
            row.setEmailVerified(false);
            return row;
        }).toList();
    }

    private static List<UserProfile> profiles(List<Long> ids) {
        return ids.stream().map(id -> {
            UserProfile row = new UserProfile();
            row.setLoginIdentityId(id);
            row.setDisplayName("Membership Boundary %04d".formatted(id - FIRST_ID));
            row.setAccountStatus(0);
            return row;
        }).toList();
    }

    private static List<UserMembershipQuota> quotas(List<Long> ids) {
        return ids.stream().map(id -> {
            UserMembershipQuota row = new UserMembershipQuota();
            row.setLoginIdentityId(id);
            row.setMembershipTier(MembershipTier.FREE.ordinal());
            row.setQuotaBalanceMinor(5_000L);
            row.setQuotaPeriodEndsAt(NOW.atOffset(ZoneOffset.UTC));
            return row;
        }).toList();
    }

    private static MembershipOrder order(
            byte[] id,
            long userId,
            MembershipOrderStatus status,
            MembershipOrderEntitlementResolution resolution) {
        MembershipOrder order = new MembershipOrder();
        order.setId(id);
        order.setLoginIdentityId(userId);
        order.setStatus(status);
        order.setEntitlementResolution(resolution);
        return order;
    }

    private static byte[] bytes(byte value) {
        byte[] id = new byte[16];
        Arrays.fill(id, value);
        return id;
    }

    private static byte[] indexedBytes(int value) {
        byte[] id = new byte[16];
        ByteBuffer.wrap(id).putInt(12, value + 1);
        return id;
    }
}
