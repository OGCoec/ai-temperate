package com.example.temperate.web.user.membership.payment.loadtest;

import com.example.temperate.service.user.membership.payment.loadtest.MembershipPaymentLoadtestControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 该 Controller 是来让本机 JMeter 触发会员支付队列恢复、一次性完成故障和读取脱敏 Redis 工件状态，不接受远程访问或任意 Redis 参数。
 *
 * <p>它只在 loadtest 开关开启时注册，所有入口再次校验 Servlet 回环地址；生产 Profile 不存在这些路由。</p>
 */
@RestController
@RequestMapping(MembershipPaymentLoadtestControlController.PATH)
@Profile({"loadtest-fast", "loadtest-realtime"})
@ConditionalOnProperty(
        prefix = "app.membership-payment.loadtest",
        name = "enabled",
        havingValue = "true")
@Tag(
        name = "会员-压测控制",
        description = "仅供本机真实时间会员支付测试触发有界队列恢复、刷盘和终态 Redis 工件验收；不提供生产业务能力。")
public final class MembershipPaymentLoadtestControlController {

    public static final String PATH =
            "/internal/test/membership-payments/loadtest-control";

    private final MembershipPaymentLoadtestControlService controlService;

    public MembershipPaymentLoadtestControlController(
            MembershipPaymentLoadtestControlService controlService) {
        this.controlService = Objects.requireNonNull(controlService);
    }

    @PostMapping("/recover-callback")
    @Operation(summary = "恢复一条超时 callback processing 任务")
    public ResponseEntity<MembershipPaymentLoadtestControlService.RecoveryProbe>
            recoverCallback(HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.recoverOneCallbackProcessing());
    }

    @PostMapping("/recover-order")
    @Operation(summary = "恢复一条超时 dirty processing 任务")
    public ResponseEntity<MembershipPaymentLoadtestControlService.RecoveryProbe>
            recoverOrder(HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.recoverOneOrderProcessing());
    }

    @PostMapping("/flush")
    @Operation(summary = "执行一次正式 callback 与 dirty 有界刷盘")
    public ResponseEntity<Void> flush(HttpServletRequest request) {
        requireLoopback(request);
        controlService.flushOneRun();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/rabbit-retry")
    @Operation(summary = "发布一条失败两次后成功确认的 RabbitMQ 探针")
    public ResponseEntity<RabbitProbeResponse> rabbitRetry(
            @RequestParam String orderId,
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(new RabbitProbeResponse(
                controlService.publishRabbitRetryProbe(orderId)));
    }

    @PostMapping("/rabbit-poison")
    @Operation(summary = "发布一条耗尽有限重投后进入 DLQ 的 RabbitMQ 探针")
    public ResponseEntity<RabbitProbeResponse> rabbitPoison(
            @RequestParam String orderId,
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(new RabbitProbeResponse(
                controlService.publishRabbitPoisonProbe(orderId)));
    }

    @GetMapping("/state")
    @Operation(summary = "读取单个订单与全局队列的脱敏 Redis 状态")
    public ResponseEntity<MembershipPaymentLoadtestControlService.RedisProbe> state(
            @RequestParam String orderId,
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.inspectOrder(orderId));
    }

    @GetMapping("/queues")
    @Operation(summary = "读取四个会员支付异步队列的脱敏基线")
    public ResponseEntity<MembershipPaymentLoadtestControlService.RedisQueueProbe> queues(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.inspectQueues());
    }

    @PostMapping("/state-batch")
    @Operation(summary = "批量检查本次终态订单的 Redis 快照与 callback marker")
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

    @PostMapping("/arm-callback-complete-failure")
    @Operation(summary = "武装一次目标订单 callback complete 前故障")
    public ResponseEntity<MembershipPaymentLoadtestControlService.FaultProbe>
            armCallbackCompleteFailure(
                    @RequestParam String orderId,
                    HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.armCallbackCompleteFailure(orderId));
    }

    @GetMapping("/faults")
    @Operation(summary = "读取已触发的一次性 callback complete 故障次数")
    public ResponseEntity<MembershipPaymentLoadtestControlService.FaultProbe> faults(
            HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.inspectFaults());
    }

    @PostMapping("/callback-hold/arm")
    @Operation(summary = "为目标订单武装有界 callback hold")
    public ResponseEntity<MembershipPaymentLoadtestControlService.CallbackHoldProbe>
            armCallbackHold(
                    @RequestParam String orderId,
                    @RequestParam int maxHoldSeconds,
                    HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.armCallbackHold(orderId, maxHoldSeconds));
    }

    @GetMapping("/callback-hold")
    @Operation(summary = "读取目标订单 callback hold 与 Marker 存在性")
    public ResponseEntity<MembershipPaymentLoadtestControlService.CallbackHoldProbe>
            callbackHold(
                    @RequestParam String orderId,
                    HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.inspectCallbackHold(orderId));
    }

    @PostMapping("/callback-hold/release")
    @Operation(summary = "幂等释放目标订单 callback hold")
    public ResponseEntity<MembershipPaymentLoadtestControlService.CallbackHoldProbe>
            releaseCallbackHold(
                    @RequestParam String orderId,
                    HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.releaseCallbackHold(orderId));
    }

    @PostMapping("/workers/pause")
    @Operation(summary = "有界暂停本机 callback 与订单刷盘 Worker")
    public ResponseEntity<MembershipPaymentLoadtestControlService.WorkerPauseProbe>
            pauseWorkers(
                    @RequestParam int maxPauseSeconds,
                    HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.pauseWorkers(maxPauseSeconds));
    }

    @GetMapping("/workers")
    @Operation(summary = "读取本机 callback 与订单刷盘 Worker 暂停状态")
    public ResponseEntity<MembershipPaymentLoadtestControlService.WorkerPauseProbe>
            workers(HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.inspectWorkers());
    }

    @PostMapping("/workers/resume")
    @Operation(summary = "幂等恢复本机 callback 与订单刷盘 Worker")
    public ResponseEntity<MembershipPaymentLoadtestControlService.WorkerPauseProbe>
            resumeWorkers(HttpServletRequest request) {
        requireLoopback(request);
        return noStore(controlService.resumeWorkers());
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
                    "Loadtest control is restricted to loopback requests.");
        }
    }

    /** 该响应只返回本次探针的公开消息 ID，Runner 不会把业务载荷或 RabbitMQ 凭据写入产物。 */
    public record RabbitProbeResponse(String messageId) {

        public RabbitProbeResponse {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("Rabbit probe message ID is required.");
            }
        }
    }

    /** 该请求只接受公开订单 ID 列表，Service 会执行数量、规范编码和重复项校验。 */
    public record OrderArtifactRequest(List<String> orderIds) {
    }
}
