package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureService;
import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentRestrictedFixtureState;
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
 * 该 Controller 是来让本机 24 小时 Runner 准备、查看和恢复四个固定 EDU/TEAM 夹具，不接受用户 ID、套餐或额度输入。
 *
 * <p>接口仅在会员支付 loadtest 开关启用且请求来自 Servlet 回环地址时注册使用；响应只包含固定 ID 和等级，
 * 原始恢复快照、额度、Token 与个人资料均不会通过 HTTP 暴露。</p>
 */
@RestController
@RequestMapping(MembershipPaymentRestrictedFixtureController.PATH)
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测受限套餐夹具",
        description = "仅供本机 loadtest-realtime Runner 事务化准备和恢复四个固定 EDU/TEAM 测试账号；限制回环地址，不接受任意用户或套餐参数。")
public final class MembershipPaymentRestrictedFixtureController {

    public static final String PATH =
            MembershipPaymentLoadtestRequestPolicy.CONTROL_ROOT + "/restricted-fixtures";

    private final MembershipPaymentRestrictedFixtureService service;

    public MembershipPaymentRestrictedFixtureController(
            MembershipPaymentRestrictedFixtureService service) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping("/prepare")
    @Operation(summary = "准备固定 EDU/TEAM 压测夹具")
    public ResponseEntity<MembershipPaymentRestrictedFixtureState> prepare(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(service.prepare());
    }

    @GetMapping
    @Operation(summary = "查看固定 EDU/TEAM 压测夹具状态")
    public ResponseEntity<MembershipPaymentRestrictedFixtureState> state(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(service.state());
    }

    @PostMapping("/restore")
    @Operation(summary = "恢复固定 EDU/TEAM 压测夹具")
    public ResponseEntity<MembershipPaymentRestrictedFixtureState> restore(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(service.restore());
    }

    private static ResponseEntity<MembershipPaymentRestrictedFixtureState> noStore(
            MembershipPaymentRestrictedFixtureState state) {
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
                    "Restricted membership fixtures are limited to loopback requests.");
        }
    }
}
