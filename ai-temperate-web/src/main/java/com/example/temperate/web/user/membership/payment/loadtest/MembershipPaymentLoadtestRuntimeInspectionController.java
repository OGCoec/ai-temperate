package com.example.temperate.web.user.membership.payment.loadtest;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来通过本机回环端点输出正式会员支付复测运行指标，仅在真实时间本地压测 Profile 注册。
 */
@RestController
@RequestMapping(MembershipPaymentLoadtestRequestPolicy.INSPECTION_ROOT)
@Profile("loadtest-realtime")
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测运行指标",
        description = "仅供本机正式复测采集 Hikari 与支付后台 Worker 聚合指标；不暴露连接信息或状态修改能力。")
public final class MembershipPaymentLoadtestRuntimeInspectionController {

    private final MembershipPaymentLoadtestRuntimeInspectionService inspectionService;

    public MembershipPaymentLoadtestRuntimeInspectionController(
            MembershipPaymentLoadtestRuntimeInspectionService inspectionService) {
        this.inspectionService = Objects.requireNonNull(inspectionService);
    }

    @GetMapping("/runtime")
    @Operation(summary = "读取本机会员支付复测运行指标")
    public ResponseEntity<MembershipPaymentLoadtestRuntimeInspectionService.RuntimeProbe>
            runtime(HttpServletRequest request) {
        requireLoopback(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(inspectionService.inspect());
    }

    private static void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddress)
                && !"::1".equals(remoteAddress)
                && !"0:0:0:0:0:0:0:1".equals(remoteAddress)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Loadtest runtime inspection is restricted to loopback requests.");
        }
    }
}
