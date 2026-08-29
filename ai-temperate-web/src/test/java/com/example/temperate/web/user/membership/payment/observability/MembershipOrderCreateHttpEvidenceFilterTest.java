package com.example.temperate.web.user.membership.payment.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentObservabilityProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 该测试是来验证真实订单创建 HTTP 墙钟证据只接收回环正式请求，并完整记录服务端接收与响应完成微秒。
 */
final class MembershipOrderCreateHttpEvidenceFilterTest {

    @Test
    void recordsOneCommittedCreateResponseWithSafeRunSegmentAndTrace() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MembershipOrderCreateHttpEvidenceFilter filter = filter();
            MockHttpServletRequest request = request("127.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, rawResponse) -> {
                rawResponse.setContentType("application/json");
                rawResponse.getWriter().write("{}");
                ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(201);
                rawResponse.flushBuffer();
            });

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("event=membership_order_create_http_completed")
                    .contains("r=run-a")
                    .contains("sg=E-P1")
                    .contains("tr=trace-a")
                    .contains("recv=1787659200123456")
                    .contains("done=1787659200123456")
                    .contains("status=201")
                    .contains("committed=true");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void recordsOnlyBoundedWarmupRunDerivedFromApplicationRunAndSegment() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MembershipOrderCreateHttpEvidenceFilter filter = filter();
            MockHttpServletRequest allowed = request("127.0.0.1");
            allowed.removeHeader("X-Loadtest-Run-Id");
            allowed.addHeader("X-Loadtest-Run-Id", "run-a-warmup-E-P1-a1");
            filter.doFilter(allowed, new MockHttpServletResponse(), (request, rawResponse) ->
                    ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(201));

            MockHttpServletRequest rejected = request("127.0.0.1");
            rejected.removeHeader("X-Loadtest-Run-Id");
            rejected.addHeader("X-Loadtest-Run-Id", "run-a-warmup-E-P1-a3");
            filter.doFilter(rejected, new MockHttpServletResponse(), (request, rawResponse) ->
                    ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(201));

            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains("r=run-a-warmup-E-P1-a1")
                    .contains("sg=E-P1");
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void ignoresNonLoopbackInvalidHeadersAndOtherRoutes() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MembershipOrderCreateHttpEvidenceFilter filter = filter();
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("192.0.2.10"), response, (request, rawResponse) -> {
                ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(201);
            });
            MockHttpServletRequest invalid = request("127.0.0.1");
            invalid.removeHeader("X-Loadtest-Run-Id");
            filter.doFilter(invalid, new MockHttpServletResponse(), (request, rawResponse) -> {
                ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(201);
            });
            MockHttpServletRequest other = request("127.0.0.1");
            other.setRequestURI("/api/user/membership-orders/ignored/payment-attempts");
            filter.doFilter(other, new MockHttpServletResponse(), (request, rawResponse) -> {
                ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(201);
            });

            assertThat(appender.list).isEmpty();
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void passesThroughLoopbackCreateWithoutLoadtestEvidenceHeaders() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MembershipOrderCreateHttpEvidenceFilter filter = filter();
            MockHttpServletRequest request = request("127.0.0.1");
            request.removeHeader("X-Loadtest-Run-Id");
            request.removeHeader("X-Loadtest-Segment");
            request.removeHeader("X-Trace-Id");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, rawResponse) ->
                    ((jakarta.servlet.http.HttpServletResponse) rawResponse).setStatus(422));

            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(appender.list).isEmpty();
        } finally {
            detachAppender(appender);
        }
    }

    private static MembershipOrderCreateHttpEvidenceFilter filter() {
        MembershipPaymentObservabilityProperties properties =
                new MembershipPaymentObservabilityProperties(
                        true,
                        false,
                        0D,
                        Duration.ofSeconds(1),
                        "run-a",
                        true);
        return new MembershipOrderCreateHttpEvidenceFilter(
                properties,
                Clock.fixed(Instant.parse("2026-08-25T12:00:00.123456Z"), ZoneOffset.UTC));
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/user/membership-orders");
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Loadtest-Run-Id", "run-a");
        request.addHeader("X-Loadtest-Segment", "E-P1");
        request.addHeader("X-Trace-Id", "trace-a");
        return request;
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger("membership.payment.order.create.http");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger("membership.payment.order.create.http");
        logger.detachAppender(appender);
        appender.stop();
    }
}
