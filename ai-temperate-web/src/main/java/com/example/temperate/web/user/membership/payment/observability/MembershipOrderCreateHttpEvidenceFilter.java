package com.example.temperate.web.user.membership.payment.observability;

import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentObservabilityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 该过滤器是来记录正式单订单创建请求的服务端接收与响应完成微秒，只在显式启用的本机实时压测中生效。
 *
 * <p>它不改变公开 API、响应状态或异常传播；非回环请求、非固定区段和不匹配当前 Run ID 的请求均直接放行且
 * 不进入资格证据，防止预热、负向探针或外来流量污染每段 QPS。</p>
 */
@Component
@Profile("loadtest-realtime")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(
        prefix = "app.membership-payment.order-create-http-evidence",
        name = "enabled",
        havingValue = "true")
public final class MembershipOrderCreateHttpEvidenceFilter extends OncePerRequestFilter {

    private static final Logger EVIDENCE_LOGGER =
            LoggerFactory.getLogger("membership.payment.order.create.http");
    private static final String CREATE_PATH = "/api/user/membership-orders";
    private static final Set<String> SEGMENTS = Set.of(
            "E-P1", "E-PR", "E-A1", "E-AR", "H-P1", "H-PR", "H-A1", "H-AR");

    private final MembershipPaymentObservabilityProperties properties;
    private final Clock clock;

    public MembershipOrderCreateHttpEvidenceFilter(
            MembershipPaymentObservabilityProperties properties,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 在认证和控制器之前取得 receivedAt，并在完整 Servlet 链返回后取得 completedAt；诊断失败不能覆盖业务响应。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!eligible(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String runId = request.getHeader("X-Loadtest-Run-Id");
        String segment = request.getHeader("X-Loadtest-Segment");
        String traceId = request.getHeader("X-Trace-Id");
        long receivedAtEpochMicros = epochMicros(clock.instant());
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                long completedAtEpochMicros = epochMicros(clock.instant());
                long durationMicros = Math.max(
                        0L, completedAtEpochMicros - receivedAtEpochMicros);
                EVIDENCE_LOGGER.info(
                        "event=membership_order_create_http_completed v=1 r={} sg={} tr={} "
                                + "recv={} done={} dur={} status={} committed={}",
                        runId,
                        segment,
                        traceId,
                        receivedAtEpochMicros,
                        completedAtEpochMicros,
                        durationMicros,
                        response.getStatus(),
                        response.isCommitted());
            } catch (RuntimeException ignored) {
                // 观测旁路只保留尽力证据；时钟或日志异常不得替换公开订单接口的原始成功或失败语义。
            }
        }
    }

    private boolean eligible(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())
                || !CREATE_PATH.equals(request.getRequestURI())
                || !loopback(request.getRemoteAddr())) {
            return false;
        }
        String runId = request.getHeader("X-Loadtest-Run-Id");
        String segment = request.getHeader("X-Loadtest-Segment");
        String traceId = request.getHeader("X-Trace-Id");
        return segment != null
                && SEGMENTS.contains(segment)
                && allowedRunId(runId, segment)
                && traceId != null
                && traceId.matches("^[A-Za-z0-9_-]{1,128}$");
    }

    private boolean allowedRunId(String runId, String segment) {
        String applicationRunId = properties.runId();
        if (applicationRunId.equals(runId)) {
            return true;
        }
        // 同规模预热必须拥有独立证据 ID，但只接受由当前应用 Run、当前固定区段和两次上限推导出的精确值。
        String warmupPrefix = applicationRunId + "-warmup-" + segment + "-a";
        return (warmupPrefix + "1").equals(runId)
                || (warmupPrefix + "2").equals(runId);
    }

    private static boolean loopback(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress)
                || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress);
    }

    private static long epochMicros(Instant instant) {
        return Math.addExact(
                Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }
}
