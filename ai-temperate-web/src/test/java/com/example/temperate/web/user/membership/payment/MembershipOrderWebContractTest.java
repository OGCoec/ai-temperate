package com.example.temperate.web.user.membership.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.model.auth.enums.MembershipTier;
import com.example.temperate.model.user.membership.payment.MembershipOrderStatus;
import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.auth.session.authentication.domain.SessionPrincipal;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderResult;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderService;
import com.example.temperate.service.user.membership.payment.order.MembershipOrderSnapshot;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptResult;
import com.example.temperate.service.user.membership.payment.order.MembershipPaymentAttemptService;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmission;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutSubmissionFields;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.example.temperate.web.auth.api.ApiErrorResponse;
import com.example.temperate.web.auth.phonecountry.component.TrustedClientIpResolver;
import com.example.temperate.web.user.membership.payment.id.MembershipOrderPublicId;
import com.example.temperate.web.user.membership.payment.id.MembershipOrderPublicIdConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
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
    void createdButUnsupportedLiuhaoCheckoutMapsToConflictWithoutTradeEvidence() {
        MembershipPaymentExceptionHandler handler = new MembershipPaymentExceptionHandler(
                Clock.fixed(NOW, ZoneOffset.UTC));
        ResponseEntity<ApiErrorResponse> response = handler.handle(
                new MembershipPaymentException(
                        MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                        "internal provider trade evidence",
                        "202608201234567890"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("LIUHAO_CHECKOUT_UNAVAILABLE");
        assertThat(response.getBody().message()).isEqualTo(
                "六号易支付已创建订单，但返回的支付入口暂时无法安全打开，请勿重复下单。");
        assertThat(response.getBody().message()).doesNotContain("202608201234567890");
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
        when(attemptService.start(
                anyLong(), any(), eq(PaymentProviderType.BAR), nullable(String.class))).thenReturn(
                new MembershipPaymentAttemptResult(
                        started, true, PaymentProviderType.BAR, null),
                new MembershipPaymentAttemptResult(
                        started, false, PaymentProviderType.BAR, null));
        CurrentUserMembershipOrderController controller =
                new CurrentUserMembershipOrderController(
                        orderService, attemptService, mock(TrustedClientIpResolver.class));
        SessionPrincipal principal = new SessionPrincipal(17L, "public-user", "member");
        MembershipOrderPublicId publicId = new MembershipOrderPublicId(
                started.orderId(), bytes((byte) 7));

        ResponseEntity<MembershipPaymentAttemptResponse> first = controller.startPayment(
                principal,
                publicId,
                new CreateMembershipPaymentAttemptRequest(PaymentProviderType.BAR),
                mock(HttpServletRequest.class));
        ResponseEntity<MembershipPaymentAttemptResponse> replay = controller.startPayment(
                principal,
                publicId,
                new CreateMembershipPaymentAttemptRequest(PaymentProviderType.BAR),
                mock(HttpServletRequest.class));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getCacheControl()).contains("no-store");
        assertThat(first.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(replay.getHeaders().getCacheControl()).contains("no-store");
        assertThat(replay.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().order().paymentStartedAt()).isEqualTo(startedAt);
        assertThat(first.getBody().checkoutSubmission()).isNull();
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().order().paymentStartedAt()).isEqualTo(startedAt);
        assertThat(replay.getBody().checkoutSubmission()).isNull();
    }

    @Test
    void barPaymentAttemptReturnsOrderAndEphemeralSignedPostSubmission() throws Exception {
        MembershipOrderService orderService = mock(MembershipOrderService.class);
        MembershipPaymentAttemptService attemptService =
                mock(MembershipPaymentAttemptService.class);
        MembershipOrderSnapshot started = snapshot(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        PaymentCheckoutSubmission submission = checkoutSubmission(started);
        when(attemptService.start(
                anyLong(), any(), eq(PaymentProviderType.BAR), nullable(String.class))).thenReturn(
                new MembershipPaymentAttemptResult(
                        started,
                        true,
                        PaymentProviderType.BAR,
                        submission));
        TrustedClientIpResolver clientIpResolver = mock(TrustedClientIpResolver.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(clientIpResolver.resolve(servletRequest))
                .thenReturn(java.util.Optional.of("203.0.113.10"));
        CurrentUserMembershipOrderController controller =
                new CurrentUserMembershipOrderController(
                        orderService, attemptService, clientIpResolver);
        SessionPrincipal principal = new SessionPrincipal(17L, "public-user", "member");
        MembershipOrderPublicId publicId = new MembershipOrderPublicId(
                started.orderId(), bytes((byte) 7));

        ResponseEntity<MembershipPaymentAttemptResponse> response = controller.startPayment(
                principal,
                publicId,
                new CreateMembershipPaymentAttemptRequest(PaymentProviderType.BAR),
                servletRequest);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().order().orderId()).isEqualTo(started.orderId());
        assertThat(response.getBody().checkoutSubmission()).isNotNull();
        assertThat(response.getBody().checkoutSubmission().provider())
                .isEqualTo(PaymentProviderType.BAR);
        assertThat(response.getBody().checkoutSubmission().checkoutMode())
                .isEqualTo(PaymentCheckoutMode.FORM_POST);
        assertThat(response.getBody().checkoutSubmission().action())
                .isEqualTo(URI.create("https://ihaveagoddamnplan.com/api/pay/submit"));
        assertThat(response.getBody().checkoutSubmission().method()).isEqualTo("POST");
        assertThat(response.getBody().checkoutSubmission().contentType())
                .isEqualTo("application/x-www-form-urlencoded");
        verify(attemptService).start(
                anyLong(), any(), eq(PaymentProviderType.BAR), eq("203.0.113.10"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                response.getBody());
        JsonNode fields = new ObjectMapper().readTree(json).path("checkoutSubmission").path("fields");
        List<String> fieldNames = new ArrayList<>();
        fields.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "pid",
                "out_trade_no",
                "type",
                "name",
                "money",
                "notify_url",
                "return_url",
                "timestamp",
                "key_version",
                "sign_type",
                "sign");
        assertThat(json).doesNotContain(
                "payUrl",
                "payUrlExpiresAt",
                "pay_type",
                "pay_url",
                "#token",
                "test-api-key",
                "Cookie",
                "checkout_token");
    }

    @Test
    void liuhaoWxpayAttemptSerializesSignedPageSubmitWithoutKeyVersion() throws Exception {
        MembershipOrderSnapshot started = snapshot(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        MembershipPaymentAttemptResponse response = MembershipPaymentAttemptResponse.from(
                new MembershipPaymentAttemptResult(
                        started,
                        true,
                        PaymentProviderType.LIUHAO,
                        liuhaoCheckoutSubmission(started)));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        JsonNode submission = new ObjectMapper().readTree(json).path("checkoutSubmission");

        assertThat(submission.path("provider").asText()).isEqualTo("LIUHAO");
        assertThat(submission.path("checkoutMode").asText()).isEqualTo("FORM_POST");
        assertThat(submission.path("action").asText())
                .isEqualTo("https://liuhao.net/api/pay/submit");
        assertThat(submission.path("method").asText()).isEqualTo("POST");
        assertThat(submission.path("contentType").asText())
                .isEqualTo("application/x-www-form-urlencoded; charset=UTF-8");
        assertThat(submission.path("fields").path("type").asText()).isEqualTo("wxpay");
        assertThat(submission.path("fields").path("sign_type").asText()).isEqualTo("RSA");
        assertThat(submission.path("fields").has("key_version")).isFalse();
    }

    @Test
    void localPaymentAttemptSerializesExplicitNullSubmissionWithoutLegacyFields() throws Exception {
        MembershipOrderSnapshot started = snapshot(
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        MembershipPaymentAttemptResponse response = MembershipPaymentAttemptResponse.from(
                new MembershipPaymentAttemptResult(
                        started,
                        true,
                        PaymentProviderType.LOCAL_SIMULATOR,
                        null));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(json).contains("\"checkoutSubmission\":null");
        assertThat(json).doesNotContain("\"provider\"", "payUrl", "payUrlExpiresAt");
    }

    @Test
    void ordinaryOrderResponseDoesNotContainCheckoutSubmission() throws Exception {
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                MembershipOrderResponse.from(snapshot()));

        assertThat(json).doesNotContain(
                "checkoutSubmission", "payUrl", "payUrlExpiresAt", "pay_type", "pay_url", "#token");
    }

    private static CurrentUserMembershipOrderController controller(
            MembershipOrderService service) {
        return new CurrentUserMembershipOrderController(
                service,
                mock(MembershipPaymentAttemptService.class),
                mock(TrustedClientIpResolver.class));
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
                MembershipOrderSnapshot.CURRENT_SCHEMA_VERSION,
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

    private static PaymentCheckoutSubmission checkoutSubmission(
            MembershipOrderSnapshot snapshot) {
        OffsetDateTime submitExpiresAt = OffsetDateTime.parse("2026-08-20T12:04:00Z");
        return new PaymentCheckoutSubmission(
                PaymentProviderType.BAR,
                PaymentCheckoutMode.FORM_POST,
                URI.create("https://ihaveagoddamnplan.com/api/pay/submit"),
                "POST",
                "application/x-www-form-urlencoded",
                submitExpiresAt,
                new PaymentCheckoutSubmissionFields(
                        "1001",
                        snapshot.orderId(),
                        snapshot.payType(),
                        "会员模拟支付订单",
                        snapshot.payAmountYuan().toPlainString(),
                        "https://niko000o.site/api/payment/bar/notify",
                        "https://niko000o.site/membership/payment/result",
                        "1787227200",
                        "1",
                        "HMAC-SHA256",
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
    }

    private static PaymentCheckoutSubmission liuhaoCheckoutSubmission(
            MembershipOrderSnapshot snapshot) {
        return new PaymentCheckoutSubmission(
                PaymentProviderType.LIUHAO,
                PaymentCheckoutMode.FORM_POST,
                URI.create("https://liuhao.net/api/pay/submit"),
                "POST",
                "application/x-www-form-urlencoded; charset=UTF-8",
                OffsetDateTime.parse("2026-08-20T12:04:00Z"),
                new PaymentCheckoutSubmissionFields(
                        "1001",
                        snapshot.orderId(),
                        "wxpay",
                        "会员支付订单",
                        snapshot.payAmountYuan().toPlainString(),
                        "https://niko000o.site/api/payment/liuhao/notify",
                        "https://niko000o.site/pages/account/payment-result",
                        "1787227200",
                        null,
                        "RSA",
                        "c2lnbmF0dXJl"));
    }

    private static byte[] bytes(byte value) {
        byte[] bytes = new byte[16];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
