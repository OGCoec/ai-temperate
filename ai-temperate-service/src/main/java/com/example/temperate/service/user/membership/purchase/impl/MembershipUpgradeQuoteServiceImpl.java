package com.example.temperate.service.user.membership.purchase.impl;

import com.example.temperate.service.user.membership.purchase.MembershipPlanPriceService;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionCommand;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionDecision;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionPolicy;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuote;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteCommand;
import com.example.temperate.service.user.membership.purchase.MembershipUpgradeQuoteService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * 该实现是来按有效订阅的剩余 UTC 自然日计算旧实付价值，并以向上取整方式保护最终收款精度。
 *
 * <p>算法只接受转换策略判定为 UPGRADE 的个人套餐；额度使用量不会参与时间价值抵扣。</p>
 */
@Service
public final class MembershipUpgradeQuoteServiceImpl
        implements MembershipUpgradeQuoteService {

    private static final int INTERMEDIATE_SCALE = 12;

    private final MembershipTransitionPolicy transitionPolicy;
    private final MembershipPlanPriceService priceService;
    private final Clock clock;

    public MembershipUpgradeQuoteServiceImpl(
            MembershipTransitionPolicy transitionPolicy,
            MembershipPlanPriceService priceService,
            Clock clock) {
        this.transitionPolicy = Objects.requireNonNull(transitionPolicy);
        this.priceService = Objects.requireNonNull(priceService);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MembershipUpgradeQuote quote(MembershipUpgradeQuoteCommand command) {
        Objects.requireNonNull(command, "Membership upgrade quote command is required.");
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        MembershipTransitionDecision decision = transitionPolicy.evaluate(
                new MembershipTransitionCommand(
                        command.currentTier(),
                        command.targetTier(),
                        command.membershipExpiresAt()));
        if (decision.transitionType() != MembershipTransitionType.UPGRADE) {
            throw new IllegalArgumentException(
                    "Membership transition is not an upgrade: type="
                            + decision.transitionType()
                            + ", reason="
                            + decision.rejectionReason());
        }
        if (now.isBefore(command.membershipStartedAt())
                || !now.isBefore(command.membershipExpiresAt())) {
            throw new IllegalArgumentException(
                    "Membership subscription is not active at quote time.");
        }

        LocalDate startDate = utcDate(command.membershipStartedAt());
        LocalDate expirationDate = utcDate(command.membershipExpiresAt());
        LocalDate quoteDate = utcDate(now);
        long subscriptionDays = ChronoUnit.DAYS.between(
                startDate, expirationDate);
        if (subscriptionDays <= 0L) {
            throw new IllegalArgumentException(
                    "Membership subscription must span at least one UTC natural day.");
        }
        // 只要精确到期时刻尚未来临，即使报价和到期落在同一 UTC 日期，也保留最后一个自然日价值。
        long calendarRemainingDays = ChronoUnit.DAYS.between(
                quoteDate, expirationDate);
        long remainingDays = Math.min(
                subscriptionDays,
                Math.max(1L, calendarRemainingDays));

        BigDecimal targetPrice = priceService.getRequiredPrice(
                command.targetTier());
        BigDecimal rawCredit = command.currentPaidAmountYuan()
                .multiply(BigDecimal.valueOf(remainingDays))
                .divide(
                        BigDecimal.valueOf(subscriptionDays),
                        INTERMEDIATE_SCALE,
                        RoundingMode.HALF_UP);
        // 中间抵扣保持高精度，只有最终应付金额向上到分，避免先舍入抵扣导致少收。
        BigDecimal payAmount = targetPrice
                .subtract(rawCredit)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.CEILING);
        BigDecimal creditAmount = targetPrice
                .subtract(payAmount)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.UNNECESSARY);

        return new MembershipUpgradeQuote(
                command.currentTier(),
                command.targetTier(),
                targetPrice,
                creditAmount,
                payAmount,
                subscriptionDays,
                remainingDays,
                now);
    }

    private static LocalDate utcDate(OffsetDateTime value) {
        return value.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
