package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentBaselineFixtureState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来让本机 Runner 准备和检查十六个固定账号的 FREE 基线，不接受用户、等级或额度参数。
 *
 * <p>接口仅在会员支付 loadtest 开关启用且 Servlet 请求来自回环地址时使用，响应只包含固定用户 ID 和等级。</p>
 */
@RestController
@RequestMapping(MembershipPaymentBaselineFixtureController.PATH)
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测统一基线",
        description = "仅供本机 loadtest-realtime Runner 事务化恢复十六个固定测试账号的 FREE 基线；限制回环地址且不接受任意用户或等级参数。")
public final class MembershipPaymentBaselineFixtureController {

    public static final String PATH =
            MembershipPaymentLoadtestRequestPolicy.CONTROL_ROOT + "/baseline-fixtures";

    private final MembershipPaymentBaselineFixtureService service;

    public MembershipPaymentBaselineFixtureController(
            MembershipPaymentBaselineFixtureService service) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping("/prepare")
    @Operation(summary = "准备十六账号 FREE 压测基线")
    public ResponseEntity<MembershipPaymentBaselineFixtureState> prepare(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(service.prepare());
    }

    @GetMapping
    @Operation(summary = "查看十六账号 FREE 压测基线")
    public ResponseEntity<MembershipPaymentBaselineFixtureState> state(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(service.state());
    }

    private static ResponseEntity<MembershipPaymentBaselineFixtureState> noStore(
            MembershipPaymentBaselineFixtureState state) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(state);
    }

    private static void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddress)
                && !"::1".equals(remoteAddress)
                && !"0:0:0:0:0:0:0:1".equals(remoteAddress)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Membership payment baseline fixtures are limited to loopback requests.");
        }
    }
}
