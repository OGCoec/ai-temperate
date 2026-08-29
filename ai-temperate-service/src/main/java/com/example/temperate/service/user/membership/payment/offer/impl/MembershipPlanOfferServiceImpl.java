package com.example.temperate.service.user.membership.payment.offer.impl;

import com.example.temperate.service.user.membership.payment.time.MembershipPaymentTime;

import com.example.temperate.mapper.user.membership.UserMembershipQuotaMapper;
import com.example.temperate.mapper.user.membership.payment.MembershipOrderMapper;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.entity.UserMembershipQuota;
import com.example.temperate.model.user.membership.payment.MembershipOrder;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOffer;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOfferResult;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOfferService;
import com.example.temperate.service.user.membership.purchase.MembershipPlanPriceService;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionCommand;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionDecision;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionPolicy;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuote;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteCommand;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来复用订单创建使用的转换和升级计价规则，一次读取用户事实后生成全部个人套餐报价。
 *
 * <p>升级历史最多查询一次并供所有目标套餐复用；本实现只读，不处理惰性到期写入、订单创建或权益变更。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
public final class MembershipPlanOfferServiceImpl implements MembershipPlanOfferService {

    private static final List<MembershipTier> PERSONAL_TIERS = List.of(
            MembershipTier.GO,
            MembershipTier.PLUS,
            MembershipTier.PRO,
            MembershipTier.MAX);
    private static final List<String> PAY_TYPES = List.of("alipay", "wxpay");
    private static final Map<MembershipTier, String> DISPLAY_NAMES = displayNames();

    private final UserMembershipQuotaMapper quotaMapper;
    private final MembershipOrderMapper orderMapper;
    private final MembershipTransitionPolicy transitionPolicy;
    private final MembershipPlanPriceService priceService;
    private final MembershipUpgradeQuoteService upgradeQuoteService;
    private final MembershipPaymentProperties properties;
    private final Clock clock;

    public MembershipPlanOfferServiceImpl(
            UserMembershipQuotaMapper quotaMapper,
            MembershipOrderMapper orderMapper,
            MembershipTransitionPolicy transitionPolicy,
            MembershipPlanPriceService priceService,
            MembershipUpgradeQuoteService upgradeQuoteService,
            MembershipPaymentProperties properties,
            Clock clock) {
        this.quotaMapper = Objects.requireNonNull(quotaMapper);
        this.orderMapper = Objects.requireNonNull(orderMapper);
        this.transitionPolicy = Objects.requireNonNull(transitionPolicy);
        this.priceService = Objects.requireNonNull(priceService);
        this.upgradeQuoteService = Objects.requireNonNull(upgradeQuoteService);
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MembershipPlanOfferResult getOffers(long loginIdentityId) {
        if (loginIdentityId <= 0L) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.INPUT_INVALID,
                    "The current login identity is invalid.");
        }
        UserMembershipQuota quota = requireQuota(loginIdentityId);
        MembershipTier storedTier = resolveTier(quota.getMembershipTier());
        List<MembershipTransitionDecision> decisions = PERSONAL_TIERS.stream()
                .map(target -> transitionPolicy.evaluate(new MembershipTransitionCommand(
                        storedTier,
                        target,
                        quota.getMembershipExpiresAt())))
                .toList();
        MembershipTier currentTier = decisions.getFirst().effectiveCurrentTier();

        // 先完成全部内存转换裁决，再按需只读取一次历史订单，防止按套餐卡产生 N+1 数据库访问。
        MembershipOrder latestPaid = decisions.stream()
                .anyMatch(decision -> decision.transitionType()
                        == MembershipTransitionType.UPGRADE)
                ? requireLatestPaidOrder(loginIdentityId, currentTier, quota)
                : null;

        List<MembershipPlanOffer> offers = new ArrayList<>();
        for (MembershipTransitionDecision decision : decisions) {
            if (decision.transitionType() == MembershipTransitionType.REJECTED) {
                continue;
            }
            BigDecimal listPrice = priceService.getRequiredPrice(decision.targetTier());
            if (decision.transitionType() == MembershipTransitionType.NEW_PURCHASE) {
                offers.add(new MembershipPlanOffer(
                        decision.targetTier(),
                        DISPLAY_NAMES.get(decision.targetTier()),
                        listPrice,
                        new BigDecimal("0.00"),
                        listPrice,
                        decision.transitionType()));
                continue;
            }
            MembershipUpgradeQuote quote = upgradeQuoteService.quote(
                    new MembershipUpgradeQuoteCommand(
                            currentTier,
                            decision.targetTier(),
                            latestPaid.getPaidAt(),
                            quota.getMembershipExpiresAt(),
                            latestPaid.getPayAmountYuan()));
            offers.add(new MembershipPlanOffer(
                    decision.targetTier(),
                    DISPLAY_NAMES.get(decision.targetTier()),
                    quote.targetPlanPriceYuan(),
                    quote.creditAmountYuan(),
                    quote.payAmountYuan(),
                    decision.transitionType()));
        }
        return new MembershipPlanOfferResult(
                currentTier,
                properties.defaultProvider(),
                properties.checkoutEnabled(),
                MembershipPaymentTime.now(clock),
                PAY_TYPES,
                offers);
    }

    private UserMembershipQuota requireQuota(long loginIdentityId) {
        UserMembershipQuota quota = quotaMapper.findByLoginIdentityId(loginIdentityId);
        if (quota == null
                || quota.getLoginIdentityId() == null
                || quota.getLoginIdentityId() != loginIdentityId) {
            throw stateConflict("The current membership quota record is unavailable.");
        }
        return quota;
    }

    private MembershipOrder requireLatestPaidOrder(
            long loginIdentityId,
            MembershipTier currentTier,
            UserMembershipQuota quota) {
        MembershipOrder latestPaid = orderMapper.findLatestPaidOrder(
                loginIdentityId,
                currentTier);
        if (latestPaid == null
                || latestPaid.getPaidAt() == null
                || latestPaid.getPayAmountYuan() == null
                || quota.getMembershipExpiresAt() == null) {
            throw new MembershipPaymentException(
                    MembershipPaymentErrorCode.MEMBERSHIP_UPGRADE_HISTORY_MISSING,
                    "A trusted paid membership period is required for upgrade pricing.");
        }
        return latestPaid;
    }

    private static MembershipTier resolveTier(Integer code) {
        if (code == null || code < 0 || code >= MembershipTier.values().length) {
            throw stateConflict("The current membership tier is invalid.");
        }
        return MembershipTier.values()[code];
    }

    private static MembershipPaymentException stateConflict(String message) {
        return new MembershipPaymentException(
                MembershipPaymentErrorCode.MEMBERSHIP_ORDER_STATE_CONFLICT,
                message);
    }

    private static Map<MembershipTier, String> displayNames() {
        EnumMap<MembershipTier, String> names = new EnumMap<>(MembershipTier.class);
        names.put(MembershipTier.GO, "Go");
        names.put(MembershipTier.PLUS, "Plus");
        names.put(MembershipTier.PRO, "Pro");
        names.put(MembershipTier.MAX, "Ultra");
        return Map.copyOf(names);
    }
}
