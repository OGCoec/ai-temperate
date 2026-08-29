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
 * 该测试是来锁定八万个持久边界账号只能全量创建或从合法前 40K 扩容，并且每轮只清理清单内订单后恢复 FREE 基线。
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
    void createsAnEmptyFixedRangeInOneHundredSixtyBatchesAndRetainsInvalidEmails() {
        when(identityMapper.findByIds(anyList())).thenReturn(List.of());
        when(profileMapper.findByLoginIdentityIds(anyList())).thenReturn(List.of());
        when(quotaMapper.findByLoginIdentityIds(anyList())).thenReturn(List.of());
        when(identityMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(profileMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(quotaMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);

        MembershipPaymentBoundaryFixtureState result = service(true).prepare();

        assertThat(result.prepared()).isTrue();
        assertThat(result.identityCount()).isEqualTo(80_000);
        ArgumentCaptor<List<UserLoginIdentity>> batches = ArgumentCaptor.forClass(List.class);
        verify(identityMapper, times(160)).batchInsertBoundaryFixtures(batches.capture());
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSize(500));
        assertThat(batches.getAllValues().stream().flatMap(List::stream))
                .extracting(UserLoginIdentity::getEmail)
                .hasSize(80_000)
                .doesNotHaveDuplicates()
                .allMatch(email -> email.endsWith(".invalid"));
        verify(profileMapper, times(160)).batchInsertBoundaryFixtures(anyList());
        verify(quotaMapper, times(160)).batchInsertBoundaryFixtures(anyList());
        verifyInvalidationInOneHundredSixtyPages();
    }

    @Test
    void expandsOnlyAnExactLegacyFortyThousandFixtureAndResetsAllQuotas() {
        when(identityMapper.findByIds(anyList())).thenAnswer(invocation -> existingLegacyRows(
                invocation.getArgument(0), MembershipPaymentBoundaryFixtureServiceImplTest::identities));
        when(profileMapper.findByLoginIdentityIds(anyList())).thenAnswer(invocation -> existingLegacyRows(
                invocation.getArgument(0), MembershipPaymentBoundaryFixtureServiceImplTest::profiles));
        when(quotaMapper.findByLoginIdentityIds(anyList())).thenAnswer(invocation -> existingLegacyRows(
                invocation.getArgument(0), MembershipPaymentBoundaryFixtureServiceImplTest::quotas));
        when(identityMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(profileMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(quotaMapper.batchInsertBoundaryFixtures(anyList())).thenReturn(500);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(500);

        MembershipPaymentBoundaryFixtureState result = service(true).prepare();

        assertThat(result.prepared()).isTrue();
        assertThat(result.identityCount()).isEqualTo(80_000);
        verify(identityMapper, times(80)).batchInsertBoundaryFixtures(anyList());
        verify(profileMapper, times(80)).batchInsertBoundaryFixtures(anyList());
        verify(quotaMapper, times(80)).batchInsertBoundaryFixtures(anyList());
        verify(quotaMapper, times(160)).batchGrantPaidMemberships(anyString());
        verifyInvalidationInOneHundredSixtyPages();
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
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L)).thenReturn(2, 0);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L)).thenReturn(0);
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
        verify(quotaMapper, times(160)).batchGrantPaidMemberships(anyString());
        verifyInvalidationInOneHundredSixtyPages();
        assertThat(identityMapper.findByIds(allIds().subList(0, 500))).hasSize(500);
    }

    @Test
    void refusesActiveOrUnresolvedOrdersBeforeDeletingAnything() {
        byte[] activeId = bytes((byte) 31);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L)).thenReturn(1);
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(
                order(activeId, FIRST_ID, MembershipOrderStatus.CLOSING, null)));

        assertThatThrownBy(() -> service(true).reset(List.of(activeId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
        verify(callbackMapper, never()).deleteByOrderIdsJson(anyString());
        verify(orderMapper, never()).deleteByIdsJson(anyString());
    }

    @Test
    void resetsOnlyExactPendingUnresolvedOrdersFromAnExplicitFailedRun() {
        stubExactTemplates();
        byte[] firstId = bytes((byte) 41);
        byte[] secondId = bytes((byte) 42);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(2, 0);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(0);
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(
                order(firstId, FIRST_ID, MembershipOrderStatus.PENDING_PAYMENT, null),
                order(secondId, FIRST_ID + 1L, MembershipOrderStatus.PENDING_PAYMENT, null)));
        when(callbackMapper.deleteByOrderIdsJson(anyString())).thenReturn(0);
        when(orderMapper.deleteByIdsJson(anyString())).thenReturn(2);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(500);

        MembershipPaymentBoundaryFixtureState result =
                service(true).resetFailedRun(List.of(firstId, secondId));

        assertThat(result.prepared()).isTrue();
        verify(callbackMapper).deleteByOrderIdsJson(anyString());
        verify(orderMapper).deleteByIdsJson(anyString());
        verify(quotaMapper, times(160)).batchGrantPaidMemberships(anyString());
        verifyInvalidationInOneHundredSixtyPages();
    }

    @Test
    void failedRunResetRefusesTerminalOrdersBeforeDeletingAnything() {
        byte[] paidId = bytes((byte) 43);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L)).thenReturn(1);
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenReturn(List.of(
                order(paidId, FIRST_ID, MembershipOrderStatus.PAID,
                        MembershipOrderEntitlementResolution.APPLIED)));

        assertThatThrownBy(() -> service(true).resetFailedRun(List.of(paidId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
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
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(2_001, 0);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
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

    @Test
    void resetsOnlyTheCurrentFiveThousandOrderWarmupAndPreservesEarlierFormalFacts() {
        stubExactTemplates();
        long groupStart = FIRST_ID + 5_000L;
        List<byte[]> warmupIds = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(MembershipPaymentBoundaryFixtureServiceImplTest::indexedBytes)
                .toList();
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(10_000, 5_000);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(10_000, 5_000);
        when(orderMapper.countByLoginIdentityIdRange(groupStart, groupStart + 5_000L))
                .thenReturn(5_000, 0);
        when(callbackMapper.countByLoginIdentityIdRange(groupStart, groupStart + 5_000L))
                .thenReturn(5_000, 0);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn(5_000, 5_000);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn(5_000, 5_000);
        when(orderMapper.hashIdsByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn("formal-orders", "formal-orders");
        when(callbackMapper.hashOrderIdsByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn("formal-callbacks", "formal-callbacks");
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenAnswer(invocation -> {
            int invocationIndex = org.mockito.Mockito.mockingDetails(orderMapper)
                    .getInvocations().stream()
                    .filter(item -> item.getMethod().getName().equals("findByIdsJsonForUpdate"))
                    .toList().size();
            int from = (invocationIndex - 1) * 2_000;
            int to = Math.min(from + 2_000, warmupIds.size());
            return java.util.stream.IntStream.range(from, to)
                    .mapToObj(index -> order(
                            warmupIds.get(index),
                            groupStart + index,
                            MembershipOrderStatus.PAID,
                            MembershipOrderEntitlementResolution.APPLIED))
                    .toList();
        });
        when(callbackMapper.deleteByOrderIdsJson(anyString())).thenReturn(2_000, 2_000, 1_000);
        when(orderMapper.deleteByIdsJson(anyString())).thenReturn(2_000, 2_000, 1_000);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(500);

        MembershipPaymentSegmentWarmupResetState result = service(true).resetSegmentWarmup(
                MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K,
                "E-PR",
                "warmup-e-pr-attempt-1",
                warmupIds);

        assertThat(result.runScale()).isEqualTo("PERFORMANCE_40K");
        assertThat(result.groupCode()).isEqualTo("E-PR");
        assertThat(result.warmupRunId()).isEqualTo("warmup-e-pr-attempt-1");
        assertThat(result.deletedOrderCount()).isEqualTo(5_000);
        assertThat(result.deletedCallbackCount()).isEqualTo(5_000);
        assertThat(result.resetQuotaCount()).isEqualTo(5_000);
        assertThat(result.currentGroupOrderCount()).isZero();
        assertThat(result.currentGroupCallbackCount()).isZero();
        assertThat(result.retainedFormalOrderCount()).isEqualTo(5_000);
        assertThat(result.retainedFormalCallbackCount()).isEqualTo(5_000);
        verify(orderMapper, times(3)).findByIdsJsonForUpdate(anyString());
        verify(orderMapper, times(3)).deleteByIdsJson(anyString());
        verify(callbackMapper, times(3)).deleteByOrderIdsJson(anyString());
        verify(quotaMapper, times(10)).batchGrantPaidMemberships(anyString());
        verify(invalidationExecutor, times(10)).evictAfterCommit(anyList());
    }

    @Test
    void refusesSegmentWarmupResetWhenEarlierFormalHashChanges() {
        stubExactTemplates();
        long groupStart = FIRST_ID + 5_000L;
        List<byte[]> warmupIds = java.util.stream.IntStream.range(0, 5_000)
                .mapToObj(MembershipPaymentBoundaryFixtureServiceImplTest::indexedBytes)
                .toList();
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(10_000, 5_000);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, FIRST_ID + 80_000L))
                .thenReturn(10_000, 5_000);
        when(orderMapper.countByLoginIdentityIdRange(groupStart, groupStart + 5_000L))
                .thenReturn(5_000, 0);
        when(callbackMapper.countByLoginIdentityIdRange(groupStart, groupStart + 5_000L))
                .thenReturn(5_000, 0);
        when(orderMapper.countByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn(5_000, 5_000);
        when(callbackMapper.countByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn(5_000, 5_000);
        when(orderMapper.hashIdsByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn("before", "after");
        when(callbackMapper.hashOrderIdsByLoginIdentityIdRange(FIRST_ID, groupStart))
                .thenReturn("callbacks", "callbacks");
        when(orderMapper.findByIdsJsonForUpdate(anyString())).thenAnswer(invocation -> {
            int invocationIndex = org.mockito.Mockito.mockingDetails(orderMapper)
                    .getInvocations().stream()
                    .filter(item -> item.getMethod().getName().equals("findByIdsJsonForUpdate"))
                    .toList().size();
            int from = (invocationIndex - 1) * 2_000;
            int to = Math.min(from + 2_000, warmupIds.size());
            return java.util.stream.IntStream.range(from, to)
                    .mapToObj(index -> order(
                            warmupIds.get(index),
                            groupStart + index,
                            MembershipOrderStatus.PAID,
                            MembershipOrderEntitlementResolution.APPLIED))
                    .toList();
        });
        when(callbackMapper.deleteByOrderIdsJson(anyString())).thenReturn(2_000, 2_000, 1_000);
        when(orderMapper.deleteByIdsJson(anyString())).thenReturn(2_000, 2_000, 1_000);
        when(quotaMapper.batchGrantPaidMemberships(anyString())).thenReturn(500);

        assertThatThrownBy(() -> service(true).resetSegmentWarmup(
                MembershipPaymentBoundaryLoadtestPolicy.RunScale.PERFORMANCE_40K,
                "E-PR",
                "warmup-e-pr-attempt-1",
                warmupIds))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("earlier formal");
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
        return java.util.stream.LongStream.range(FIRST_ID, FIRST_ID + 80_000L).boxed().toList();
    }

    private void verifyInvalidationInOneHundredSixtyPages() {
        ArgumentCaptor<List<Long>> batches = ArgumentCaptor.forClass(List.class);
        verify(invalidationExecutor, times(160)).evictAfterCommit(batches.capture());

        MembershipPaymentBoundaryLoadtestPolicy policy =
                new MembershipPaymentBoundaryLoadtestPolicy();
        assertThat(batches.getAllValues())
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 160)
                        .mapToObj(policy::pageUserIds)
                        .toList())
                .allSatisfy(batch -> assertThat(batch).hasSize(500));
    }

    private static <T> List<T> existingLegacyRows(
            List<Long> ids,
            java.util.function.Function<List<Long>, List<T>> factory) {
        return ids.getFirst() < FIRST_ID + 40_000L ? factory.apply(ids) : List.of();
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
