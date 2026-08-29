package com.example.temperate.web.user.membership.payment;

import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.membership.payment.offer.MembershipPlanOfferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来为已认证用户提供个人会员套餐的只读服务端报价，不创建订单或修改会员状态。
 */
@RestController
@RequestMapping("/api/user/membership-plan-offers")
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-套餐报价",
        description = "供已通过会话认证的 H5 用户读取当前允许购买的个人会员套餐、服务端金额和支付环境；接口只读，不接受客户端价格、不创建订单、不发放会员权益。")
public final class CurrentUserMembershipPlanOfferController {

    private final MembershipPlanOfferService offerService;

    public CurrentUserMembershipPlanOfferController(
            MembershipPlanOfferService offerService) {
        this.offerService = Objects.requireNonNull(offerService);
    }

    @GetMapping
    @Operation(
            summary = "查询当前用户可购买的会员套餐",
            description = "返回 Go、Plus、Pro、Ultra 中当前允许的新购或升级报价；所有金额均为服务端两位小数字符串，响应禁止浏览器和 CDN 缓存。")
    public ResponseEntity<MembershipPlanOfferResponse> getOffers(
            @AuthenticationPrincipal SessionPrincipal principal) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("CDN-Cache-Control", "no-store")
                .body(MembershipPlanOfferResponse.from(
                        offerService.getOffers(principal.userId())));
    }
}
