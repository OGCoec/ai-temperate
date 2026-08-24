package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryFixtureState;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBoundaryTokenService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来向本机 JMeter Runner 暴露固定四万用户夹具、精确本轮清理和五百用户分页令牌。
 *
 * <p>入口只在真实时间压测 Profile 与独立开关同时启用时注册，并逐请求校验回环来源；它不允许调用方指定用户范围、
 * 套餐或数据库条件，也不在夹具响应中返回邮箱、回调标识或订单清单。</p>
 */
@RestController
@Profile("loadtest-realtime")
@RequestMapping(MembershipPaymentBoundaryLoadtestController.PATH)
@ConditionalOnProperty(
        prefix = "app.membership-payment.boundary-loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-毫秒边界压测",
        description = "仅供回环 JMeter Runner 管理固定四万测试账号、分页签发短期令牌并精确清理本轮支付数据；不接受任意用户或套餐参数。")
public final class MembershipPaymentBoundaryLoadtestController {

    public static final String PATH =
            "/internal/test/membership-payments/millisecond-boundary";
    private static final int MAX_ORDER_IDS = 40_000;

    private final MembershipPaymentBoundaryFixtureService fixtureService;
    private final MembershipPaymentBoundaryTokenService tokenService;
    private final HybridBase64UrlCodec orderIdCodec;

    public MembershipPaymentBoundaryLoadtestController(
            MembershipPaymentBoundaryFixtureService fixtureService,
            MembershipPaymentBoundaryTokenService tokenService,
            HybridBase64UrlCodec orderIdCodec) {
        this.fixtureService = Objects.requireNonNull(fixtureService);
        this.tokenService = Objects.requireNonNull(tokenService);
        this.orderIdCodec = Objects.requireNonNull(orderIdCodec);
    }

    @PostMapping("/prepare")
    @Operation(summary = "创建或验证固定四万用户边界压测模板")
    public ResponseEntity<MembershipPaymentBoundaryFixtureState> prepare(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(fixtureService.prepare());
    }

    @GetMapping("/state")
    @Operation(summary = "读取固定边界压测模板的非敏感计数")
    public ResponseEntity<MembershipPaymentBoundaryFixtureState> state(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(fixtureService.state());
    }

    @PostMapping("/reset")
    @Operation(summary = "按本轮完整订单清单清理支付数据并恢复 FREE 基线")
    public ResponseEntity<MembershipPaymentBoundaryFixtureState> reset(
            HttpServletRequest request,
            @RequestBody ResetRequest body) {
        requireLoopback(request);
        ResetRequest safeBody = Objects.requireNonNull(body, "Reset request is required.");
        List<byte[]> orderIds = safeBody.orderIds().stream()
                .map(orderIdCodec::decode)
                .toList();
        return noStore(fixtureService.reset(orderIds));
    }

    @PostMapping("/tokens/{page}")
    @Operation(summary = "签发一个固定五百用户页的十五小时 Access Token")
    public ResponseEntity<List<MembershipPaymentLoadtestToken>> tokens(
            HttpServletRequest request,
            @PathVariable int page) {
        requireLoopback(request);
        return noStore(tokenService.issuePage(page));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static void requireLoopback(HttpServletRequest request) {
        String address = request == null ? null : request.getRemoteAddr();
        if (!"127.0.0.1".equals(address)
                && !"::1".equals(address)
                && !"0:0:0:0:0:0:0:1".equals(address)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Boundary loadtest control is restricted to loopback requests.");
        }
    }

    /**
     * 该请求只承载 Runner 已持有的本轮订单公共 ID，数量有界且不能借此指定用户范围或删除条件。
     */
    public record ResetRequest(List<String> orderIds) {

        public ResetRequest {
            orderIds = orderIds == null ? List.of() : List.copyOf(orderIds);
            if (orderIds.size() > MAX_ORDER_IDS
                    || orderIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException(
                        "Boundary reset order manifest is invalid.");
            }
        }
    }
}
