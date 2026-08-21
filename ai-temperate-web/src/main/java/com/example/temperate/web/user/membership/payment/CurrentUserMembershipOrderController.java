package com.example.temperate.web.user.membership.payment;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderCreateCommand;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import com.example.temperate.web.user.membership.payment.id.MembershipOrderPublicId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 该 Controller 是来为已认证 H5、Android、curl 和 Apifox 客户端创建、查询及取消当前用户会员支付订单。
 *
 * <p>接口只编排订单状态，不接收客户端价格、不发放会员权益，也不提供模拟支付页面或真实支付跳转。</p>
 */
@RestController
@RequestMapping("/api/user/membership-orders")
@ConditionalOnProperty(
        prefix = "app.membership-payment",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-支付订单",
        description = "供已通过会话认证的用户创建服务端定价的会员模拟支付订单、查询 Redis 优先的实时状态及取消待支付订单；接口执行资源级所有权校验，不负责真实支付、退款或会员权益发放。")
public final class CurrentUserMembershipOrderController {

    private static final String CDN_CACHE_CONTROL = "CDN-Cache-Control";

    private final MembershipOrderService membershipOrderService;
    private final MembershipPaymentAttemptService paymentAttemptService;

    public CurrentUserMembershipOrderController(
            MembershipOrderService membershipOrderService,
            MembershipPaymentAttemptService paymentAttemptService) {
        this.membershipOrderService = Objects.requireNonNull(membershipOrderService);
        this.paymentAttemptService = Objects.requireNonNull(paymentAttemptService);
    }

    @PostMapping
    @Operation(
            summary = "创建当前用户会员支付订单",
            description = "请求只提交目标会员等级、alipay/wxpay 支付方式和 UUIDv4 幂等键；金额由服务端定价。同一意图首次创建返回 201，确认过的幂等重放返回 200。")
    public ResponseEntity<MembershipOrderResponse> create(
            @AuthenticationPrincipal SessionPrincipal principal,
            @Valid @RequestBody CreateMembershipOrderRequest request) {
        MembershipOrderResult result = membershipOrderService.create(
                principal.userId(),
                new MembershipOrderCreateCommand(
                        request.targetTier(),
                        request.payType(),
                        request.idempotencyKey()));
        MembershipOrderResponse response = MembershipOrderResponse.from(result.snapshot());
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(noStore())
                .header(CDN_CACHE_CONTROL, "no-store");
        if (result.created()) {
            builder.location(URI.create(
                    "/api/user/membership-orders/" + response.orderId()));
        }
        return builder.body(response);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "查询当前用户会员支付订单实时状态")
    public ResponseEntity<MembershipOrderResponse> get(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(schema = @Schema(
                    minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                    maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                    pattern = HybridBase64UrlCodec.ENCODED_PATTERN,
                    example = "AaAjECcaAQGqi_h2Rl1PiA"))
            MembershipOrderPublicId orderId) {
        return response(membershipOrderService.getOwned(
                principal.userId(), orderId.internalValue()));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(
            summary = "取消当前用户待支付会员订单",
            description = "仅 PENDING_PAYMENT 可取消；存在回调 marker 或订单已进入 CLOSING/PAID/CLOSED 时返回 409。")
    public ResponseEntity<MembershipOrderResponse> cancel(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(schema = @Schema(
                    minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                    maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                    pattern = HybridBase64UrlCodec.ENCODED_PATTERN,
                    example = "AaAjECcaAQGqi_h2Rl1PiA"))
            MembershipOrderPublicId orderId) {
        return response(membershipOrderService.cancel(
                principal.userId(), orderId.internalValue()));
    }

    @PostMapping("/{orderId}/payment-attempts")
    @Operation(
            summary = "记录当前用户发起会员支付",
            description = "仅未过期的 PENDING_PAYMENT 可首次记录并返回 201；有效期内幂等重放返回原 paymentStartedAt 和 200，其余状态返回 409。")
    public ResponseEntity<MembershipOrderResponse> startPayment(
            @AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable
            @Parameter(schema = @Schema(
                    minLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                    maxLength = HybridBase64UrlCodec.ENCODED_LENGTH,
                    pattern = HybridBase64UrlCodec.ENCODED_PATTERN,
                    example = "AaAjECcaAQGqi_h2Rl1PiA"))
            MembershipOrderPublicId orderId) {
        MembershipPaymentAttemptResult result = paymentAttemptService.start(
                principal.userId(), orderId.internalValue());
        return ResponseEntity
                .status(result.started() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(noStore())
                .header(CDN_CACHE_CONTROL, "no-store")
                .body(MembershipOrderResponse.from(result.snapshot()));
    }

    private static ResponseEntity<MembershipOrderResponse> response(
            MembershipOrderResult result) {
        return ResponseEntity.ok()
                .cacheControl(noStore())
                .header(CDN_CACHE_CONTROL, "no-store")
                .body(MembershipOrderResponse.from(result.snapshot()));
    }

    /** 用户订单响应禁止浏览器、共享缓存和 CDN 保存，避免过期状态误导取消或支付决策。 */
    private static CacheControl noStore() {
        return CacheControl.noStore().cachePrivate();
    }
}
