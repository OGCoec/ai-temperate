package com.example.temperate.web.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOffer;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOfferResult;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOfferService;
import com.example.temperate.service.user.membership.purchase.MembershipTransitionType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 该测试是来约束当前用户套餐报价接口的身份传递、金额字符串和禁止缓存响应。
 */
final class CurrentUserMembershipPlanOfferControllerTest {

    @Test
    void returnsAuthenticatedUsersServerPricedOffersWithoutCaching() {
        MembershipPlanOfferService service = mock(MembershipPlanOfferService.class);
        OffsetDateTime quotedAt = OffsetDateTime.parse("2026-08-21T12:00:00Z");
        when(service.getOffers(17L)).thenReturn(new MembershipPlanOfferResult(
                MembershipTier.FREE,
                PaymentProviderType.BAR,
                true,
                quotedAt,
                List.of("alipay", "wxpay"),
                List.of(new MembershipPlanOffer(
                        MembershipTier.GO,
                        "Go",
                        new BigDecimal("0.05"),
                        new BigDecimal("0.00"),
                        new BigDecimal("0.05"),
                        MembershipTransitionType.NEW_PURCHASE))));
        CurrentUserMembershipPlanOfferController controller =
                new CurrentUserMembershipPlanOfferController(service);

        ResponseEntity<MembershipPlanOfferResponse> response = controller.getOffers(
                new SessionPrincipal(17L, "public-user", "member"));

        verify(service).getOffers(17L);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().currentTier()).isEqualTo(MembershipTier.FREE);
        assertThat(response.getBody().provider()).isEqualTo(PaymentProviderType.BAR);
        assertThat(response.getBody().offers()).singleElement().satisfies(offer -> {
            assertThat(offer.displayName()).isEqualTo("Go");
            assertThat(offer.listPriceYuan()).isEqualTo("0.05");
            assertThat(offer.creditAmountYuan()).isEqualTo("0.00");
            assertThat(offer.payAmountYuan()).isEqualTo("0.05");
        });
    }
}
