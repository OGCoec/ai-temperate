package com.example.temperate.service.user.membership.payment.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.offer.impl.MembershipPlanOfferServiceImpl;
import com.example.temperate.service.user.membership.purchase.MembershipPlanPriceService;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionPolicy;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteService;
import com.example.temperate.service.user.membership.purchase.impl.FixedMembershipPlanPriceServiceImpl;
import com.example.temperate.service.user.membership.purchase.impl.MembershipTransitionPolicyImpl;
import com.example.temperate.service.user.membership.purchase.impl.MembershipUpgradeQuoteServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来约束个人套餐报价只读聚合、升级历史单次查询和服务端金额计算边界。
 */
final class MembershipPlanOfferServiceImplTest {

    private static final long USER_ID = 17L;
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private UserMembershipQuotaMapper quotaMapper;
    private MembershipOrderMapper orderMapper;
    private MembershipPlanOfferService service;

    @BeforeEach
    void setUp() {
        quotaMapper = mock(UserMembershipQuotaMapper.class);
        orderMapper = mock(MembershipOrderMapper.class);
        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.checkoutEnabled()).thenReturn(true);
        when(properties.defaultProvider()).thenReturn(PaymentProviderType.BAR);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MembershipPlanPriceService priceService = new FixedMembershipPlanPriceServiceImpl();
        MembershipTransitionPolicy transitionPolicy =
                new MembershipTransitionPolicyImpl(clock);
        MembershipUpgradeQuoteService upgradeQuoteService =
                new MembershipUpgradeQuoteServiceImpl(
                        transitionPolicy, priceService, clock);
        service = new MembershipPlanOfferServiceImpl(
                quotaMapper,
                orderMapper,
                transitionPolicy,
                priceService,
                upgradeQuoteService,
                properties,
                clock);
    }

    @Test
    void freeUserReceivesFourPersonalOffersWithoutPaidOrderLookup() {
        when(quotaMapper.findByLoginIdentityId(USER_ID)).thenReturn(
                quota(MembershipTier.FREE, null));

        MembershipPlanOfferResult result = service.getOffers(USER_ID);

        assertThat(result.currentTier()).isEqualTo(MembershipTier.FREE);
        assertThat(result.provider()).isEqualTo(PaymentProviderType.BAR);
        assertThat(result.checkoutEnabled()).isTrue();
        assertThat(result.payTypes()).containsExactly("alipay", "wxpay");
        assertThat(result.offers())
                .extracting(MembershipPlanOffer::targetTier)
                .containsExactly(
                        MembershipTier.GO,
                        MembershipTier.PLUS,
                        MembershipTier.PRO,
                        MembershipTier.MAX);
        assertThat(result.offers())
                .extracting(MembershipPlanOffer::displayName)
                .containsExactly("Go", "Plus", "Pro", "Ultra");
        assertThat(result.offers())
                .extracting(MembershipPlanOffer::payAmountYuan)
                .containsExactly(
                        new BigDecimal("0.05"),
                        new BigDecimal("0.20"),
                        new BigDecimal("0.30"),
                        new BigDecimal("0.50"));
        assertThat(result.offers())
                .allMatch(offer -> offer.transitionType()
                        == MembershipTransitionType.NEW_PURCHASE);
        verify(orderMapper, never()).findLatestPaidOrder(
                USER_ID, MembershipTier.FREE);
    }

    @Test
    void activePlusUpgradeReadsPaidHistoryOnceForAllHigherOffers() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(quotaMapper.findByLoginIdentityId(USER_ID)).thenReturn(
                quota(MembershipTier.PLUS, now.plusDays(15)));
        MembershipOrder latestPaid = new MembershipOrder();
        latestPaid.setPaidAt(now.minusDays(15));
        latestPaid.setPayAmountYuan(new BigDecimal("0.20"));
        when(orderMapper.findLatestPaidOrder(
                USER_ID, MembershipTier.PLUS))
                .thenReturn(latestPaid);

        MembershipPlanOfferResult result = service.getOffers(USER_ID);

        assertThat(result.currentTier()).isEqualTo(MembershipTier.PLUS);
        assertThat(result.offers())
                .extracting(MembershipPlanOffer::targetTier)
                .containsExactly(MembershipTier.PRO, MembershipTier.MAX);
        assertThat(result.offers())
                .extracting(MembershipPlanOffer::creditAmountYuan)
                .containsExactly(new BigDecimal("0.10"), new BigDecimal("0.10"));
        assertThat(result.offers())
                .extracting(MembershipPlanOffer::payAmountYuan)
                .containsExactly(new BigDecimal("0.20"), new BigDecimal("0.40"));
        verify(quotaMapper).findByLoginIdentityId(USER_ID);
        verify(orderMapper).findLatestPaidOrder(
                USER_ID, MembershipTier.PLUS);
    }

    @Test
    void missingTrustedUpgradeHistoryReturnsControlledConflict() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(quotaMapper.findByLoginIdentityId(USER_ID)).thenReturn(
                quota(MembershipTier.GO, now.plusDays(10)));

        assertThatThrownBy(() -> service.getOffers(USER_ID))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.MEMBERSHIP_UPGRADE_HISTORY_MISSING));
    }

    @Test
    void lockedTeamTierHasNoPersonalTransitionOffers() {
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(quotaMapper.findByLoginIdentityId(USER_ID)).thenReturn(
                quota(MembershipTier.TEAM, now.plusDays(10)));

        MembershipPlanOfferResult result = service.getOffers(USER_ID);

        assertThat(result.currentTier()).isEqualTo(MembershipTier.TEAM);
        assertThat(result.offers()).isEmpty();
        verify(orderMapper, never()).findLatestPaidOrder(
                USER_ID, MembershipTier.TEAM);
    }

    private static UserMembershipQuota quota(
            MembershipTier tier,
            OffsetDateTime membershipExpiresAt) {
        UserMembershipQuota quota = new UserMembershipQuota();
        quota.setLoginIdentityId(USER_ID);
        quota.setMembershipTier(tier.ordinal());
        quota.setMembershipExpiresAt(membershipExpiresAt);
        return quota;
    }
}
