package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestControlService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来为本地真实时间和共享 BAR 浸泡测试提供回环只读验收入口，只读取异步队列与指定订单的 Redis 残留工件。
 *
 * <p>它不提供 Worker 暂停、故障注入、Marker 控制或任意 Redis Key 查询；共享环境因此能够执行最终清理核验而不会获得破坏性控制能力。</p>
 */
@RestController
@RequestMapping(MembershipPaymentLoadtestRequestPolicy.INSPECTION_ROOT)
@Profile({"loadtest-realtime", "loadtest-bar"})
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测只读验收",
        description = "仅供服务器回环读取会员支付异步队列和指定测试订单的脱敏 Redis 残留状态；不提供任何状态修改能力。")
public final class MembershipPaymentLoadtestInspectionController {

    private final MembershipPaymentLoadtestControlService controlService;

    public MembershipPaymentLoadtestInspectionController(
            MembershipPaymentLoadtestControlService controlService) {
        this.controlService = Objects.requireNonNull(controlService);
    }

    @GetMapping("/queues")
    @Operation(summary = "读取会员支付异步队列的脱敏状态")
    public ResponseEntity<MembershipPaymentLoadtestControlService.RedisQueueProbe> queues(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.inspectQueues());
    }

    @PostMapping("/state-batch")
    @Operation(summary = "批量检查测试订单的 Redis 快照与回调 Marker")
    public ResponseEntity<List<MembershipPaymentLoadtestControlService.OrderArtifactProbe>>
            stateBatch(
                    @RequestBody OrderArtifactRequest body,
                    HttpServletRequest request) {
        requireLoopback(request);
        if (body == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Loadtest order artifact request is required.");
        }
        return noStore(controlService.inspectOrderArtifacts(body.orderIds()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static void requireLoopback(HttpServletRequest request) {
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        if (!"127.0.0.1".equals(remoteAddress)
                && !"::1".equals(remoteAddress)
                && !"0:0:0:0:0:0:0:1".equals(remoteAddress)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Loadtest inspection is restricted to loopback requests.");
        }
    }

    /** 该请求只携带本轮公开订单 ID；Service 会再次执行数量、格式和批量边界校验。 */
    public record OrderArtifactRequest(List<String> orderIds) {}
}
