package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.loadtest.MembershipQuotaLoadtestFaultGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来让 W16 Runner 在第二回环实例武装并读取一次额度预扣事务回滚证据。
 *
 * <p>入口只在 loadtest-bar 推理替身显式开启时注册，并再次校验 Servlet 回环来源；
 * Service 还会拒绝白名单外用户，响应不包含 Token、幂等摘要或额度值。</p>
 */
@RestController
@RequestMapping(MembershipPaymentLoadtestRequestPolicy.INFERENCE_STUB_ROOT
        + "/controls/quota-rollback")
@Profile("loadtest-bar")
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest.inference-stub",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测额度回滚",
        description = "仅供 W16 第二回环实例武装一次白名单用户预扣回滚并读取低基数触发证据。")
public final class MembershipQuotaLoadtestFaultController {

    private final MembershipQuotaLoadtestFaultGate faultGate;

    public MembershipQuotaLoadtestFaultController(
            MembershipQuotaLoadtestFaultGate faultGate) {
        this.faultGate = Objects.requireNonNull(faultGate);
    }

    @PostMapping("/arm")
    @Operation(summary = "武装下一次目标用户额度预扣事务回滚")
    public ResponseEntity<RollbackProbe> arm(
            @RequestParam long userId,
            HttpServletRequest request) {
        requireLoopback(request);
        long count = faultGate.armReservationRollback(userId);
        return noStore(new RollbackProbe(true, count));
    }

    @GetMapping
    @Operation(summary = "读取额度预扣回滚武装状态和单调触发次数")
    public ResponseEntity<RollbackProbe> state(HttpServletRequest request) {
        requireLoopback(request);
        return noStore(new RollbackProbe(
                faultGate.reservationRollbackArmed(),
                faultGate.reservationRollbackFailureCount()));
    }

    private static ResponseEntity<RollbackProbe> noStore(RollbackProbe body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static void requireLoopback(HttpServletRequest request) {
        String address = request == null ? null : request.getRemoteAddr();
        if (!"127.0.0.1".equals(address)
                && !"::1".equals(address)
                && !"0:0:0:0:0:0:0:1".equals(address)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Loadtest quota rollback control is restricted to loopback requests.");
        }
    }

    /** 该探针只返回武装布尔值和进程内单调次数，不暴露目标用户或请求关联值。 */
    public record RollbackProbe(boolean armed, long failureCount) {
    }
}
