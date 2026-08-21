package com.example.temperate.web.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.user.membership.payment.id.MembershipOrderPublicId;
import com.example.temperate.web.user.membership.payment.id.MembershipOrderPublicIdConverter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 该 Web 契约测试是来约束会员订单 201/200 幂等语义、22 位 Base64URL 转换、无缓存响应和所有权隐藏的 404。
 */
final class MembershipOrderWebContractTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void firstCreateReturns201LocationAndNoStoreWhileReplayReturns200() {
        MembershipOrderService service = mock(MembershipOrderService.class);
        MembershipOrderSnapshot snapshot = snapshot();
        when(service.create(anyLong(), any())).thenReturn(
                new MembershipOrderResult(snapshot, true),
                new MembershipOrderResult(snapshot, false));
        CurrentUserMembershipOrderController controller =
                controller(service);
        SessionPrincipal principal = new SessionPrincipal(17L, "public-user", "member");
        CreateMembershipOrderRequest request = new CreateMembershipOrderRequest(
                MembershipTier.PLUS,
                "alipay",
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));

        ResponseEntity<MembershipOrderResponse> created = controller.create(principal, request);
        ResponseEntity<MembershipOrderResponse> replay = controller.create(principal, request);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation().toString())
                .endsWith("/" + snapshot.orderId());
        assertThat(created.getHeaders().getCacheControl()).contains("no-store");
        assertThat(created.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void converterRoundTripsCanonicalBase64UrlAndReturnsDefensiveBytes() {
        HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
        byte[] internal = bytes((byte) 7);
        String encoded = codec.encode(internal);

        MembershipOrderPublicId publicId =
                new MembershipOrderPublicIdConverter(codec).convert(encoded);
        byte[] exposed = publicId.internalValue();
        exposed[0] = 0;

        assertThat(publicId.encoded()).hasSize(22);
        assertThat(publicId.internalValue()).isEqualTo(internal);
    }

    @Test
    void converterRejectsLegacyMembershipUlid() {
        MembershipOrderPublicIdConverter converter =
                new MembershipOrderPublicIdConverter(new HybridBase64UrlCodec());

        assertThatThrownBy(() -> converter.convert("01M0HH09RT040TN2ZRES35TKW8"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingOrForeignOrderMapsTo404WithoutOwnershipDetail() {
        MembershipPaymentExceptionHandler handler = new MembershipPaymentExceptionHandler(
                Clock.fixed(NOW, ZoneOffset.UTC));
        ResponseEntity<ApiErrorResponse> response = handler.handle(
                new MembershipPaymentException(
                        MembershipPaymentErrorCode.MEMBERSHIP_ORDER_NOT_FOUND,
                        "internal ownership detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("会员支付订单不存在。");
        assertThat(response.getBody().message()).doesNotContain("ownership");
    }

    @Test
    void currentUserGetUsesAuthenticatedOwnerAndReturnsRealtimeStatusWithoutCaching() {
        MembershipOrderService service = mock(MembershipOrderService.class);
        MembershipOrderSnapshot snapshot = snapshot();
        when(service.getOwned(anyLong(), any())).thenReturn(
                new MembershipOrderResult(snapshot, false));
        CurrentUserMembershipOrderController controller =
                controller(service);
        SessionPrincipal principal = new SessionPrincipal(17L, "public-user", "member");
        MembershipOrderPublicId publicId = new MembershipOrderPublicId(
                snapshot.orderId(), bytes((byte) 7));

        ResponseEntity<MembershipOrderResponse> response = controller.get(
                principal, publicId);

        ArgumentCaptor<byte[]> idCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(service).getOwned(org.mockito.ArgumentMatchers.eq(17L), idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(bytes((byte) 7));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(MembershipOrderStatus.PENDING_PAYMENT);
    }

    @Test
    void currentUserCancelUsesAuthenticatedOwnerAndReturnsCancelledStatus() {
        MembershipOrderService service = mock(MembershipOrderService.class);
        MembershipOrderSnapshot cancelled = snapshot(MembershipOrderStatus.CANCELLED);
        when(service.cancel(anyLong(), any())).thenReturn(
                new MembershipOrderResult(cancelled, false));
        CurrentUserMembershipOrderController controller =
                controller(service);
        SessionPrincipal principal = new SessionPrincipal(17L, "public-user", "member");
        MembershipOrderPublicId publicId = new MembershipOrderPublicId(
                cancelled.orderId(), bytes((byte) 7));

        ResponseEntity<MembershipOrderResponse> response = controller.cancel(
                principal, publicId);

        ArgumentCaptor<byte[]> idCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(service).cancel(org.mockito.ArgumentMatchers.eq(17L), idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(bytes((byte) 7));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .isEqualTo(MembershipOrderStatus.CANCELLED);
    }

    @Test
    void firstPaymentAttemptReturns201AndReplayReturns200WithOriginalStartTime() {
        MembershipOrderService orderService = mock(MembershipOrderService.class);
        MembershipPaymentAttemptService attemptService =
                mock(MembershipPaymentAttemptService.class);
        OffsetDateTime startedAt = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        MembershipOrderSnapshot started = snapshot(startedAt);
        when(attemptService.start(anyLong(), any())).thenReturn(
                new MembershipPaymentAttemptResult(started, true),
                new MembershipPaymentAttemptResult(started, false));
        CurrentUserMembershipOrderController controller =
                new CurrentUserMembershipOrderController(orderService, attemptService);
        SessionPrincipal principal = new SessionPrincipal(17L, "public-user", "member");
        MembershipOrderPublicId publicId = new MembershipOrderPublicId(
                started.orderId(), bytes((byte) 7));

        ResponseEntity<MembershipOrderResponse> first = controller.startPayment(
                principal, publicId);
        ResponseEntity<MembershipOrderResponse> replay = controller.startPayment(
                principal, publicId);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().paymentStartedAt()).isEqualTo(startedAt);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().paymentStartedAt()).isEqualTo(startedAt);
    }

    private static CurrentUserMembershipOrderController controller(
            MembershipOrderService service) {
        return new CurrentUserMembershipOrderController(
                service, mock(MembershipPaymentAttemptService.class));
    }

    private static MembershipOrderSnapshot snapshot() {
        return snapshot(MembershipOrderStatus.PENDING_PAYMENT);
    }

    private static MembershipOrderSnapshot snapshot(MembershipOrderStatus status) {
        return snapshot(status, null);
    }

    private static MembershipOrderSnapshot snapshot(OffsetDateTime paymentStartedAt) {
        return snapshot(MembershipOrderStatus.PENDING_PAYMENT, paymentStartedAt);
    }

    private static MembershipOrderSnapshot snapshot(
            MembershipOrderStatus status,
            OffsetDateTime paymentStartedAt) {
        byte[] id = bytes((byte) 7);
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new MembershipOrderSnapshot(
                1,
                new HybridBase64UrlCodec().encode(id),
                17L,
                MembershipTier.PLUS,
                new BigDecimal("20.00"),
                "alipay",
                status,
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                null,
                paymentStartedAt,
                now.plusMinutes(5),
                null,
                null,
                1L,
                now,
                now);
    }

    private static byte[] bytes(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
