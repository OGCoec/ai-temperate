package com.example.temperate.service.user.membership.payment.provider.liuhao.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutMode;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 该测试是来固定六号 V2 页面提交、统一下单、查询、关单和退款的 RSA 合同及保守失败边界，测试不会连接公网。
 */
@ExtendWith(OutputCaptureExtension.class)
final class LiuhaoPaymentRestClientImplTest {

    private static final String BASE_URL = "https://liuhao.net";
    private static final String ORDER_ID = "AaBVT8qWAQGYyiS9xjCEcg";
    private static final String TRADE_NO = "2026083108542370629";
    private static final String PID = "1001";
    private static final Instant NOW = Instant.parse("2026-08-31T08:59:23Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private LiuhaoPaymentRestClientImpl client;
    private LiuhaoPaymentSignatureServiceImpl signatures;
    private KeyPair platform;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair merchant = keyPair();
        platform = keyPair();
        signatures = new LiuhaoPaymentSignatureServiceImpl(
                encoded(merchant.getPrivate().getEncoded()),
                encoded(platform.getPublic().getEncoded()),
                encoded(merchant.getPublic().getEncoded()));
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        MembershipPaymentProperties properties = mock(MembershipPaymentProperties.class);
        when(properties.liuhao()).thenReturn(new MembershipPaymentProperties.Liuhao(
                true,
                URI.create(BASE_URL),
                PID,
                "merchant-private-key-is-held-by-signature-test-service",
                "platform-public-key-is-held-by-signature-test-service",
                "merchant-public-key-is-held-by-signature-test-service",
                URI.create("https://niko000o.site/api/payment/liuhao/notify"),
                URI.create("https://niko000o.site/pages/account/payment-result"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65_536,
                Duration.ofMinutes(5)));
        meterRegistry = new SimpleMeterRegistry();
        client = new LiuhaoPaymentRestClientImpl(
                builder.build(),
                objectMapper,
                signatures,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new MembershipPaymentMetrics(meterRegistry));
    }

    @Test
    void keepsLegacySignedWxpayPageSubmissionAsAnUnusedCompatibilityDescription(
            CapturedOutput output) {
        var result = client.createCheckout(new PaymentCheckoutCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单"));

        assertThat(result.providerTradeNo()).isNull();
        assertThat(result.created()).isTrue();
        assertThat(result.checkoutSubmission().checkoutMode())
                .isEqualTo(PaymentCheckoutMode.FORM_POST);
        assertThat(result.checkoutSubmission().action())
                .isEqualTo(URI.create(BASE_URL + "/api/pay/submit"));
        assertThat(result.checkoutSubmission().method()).isEqualTo("POST");
        assertThat(result.checkoutSubmission().fields().outTradeNo()).isEqualTo(ORDER_ID);
        assertThat(result.checkoutSubmission().fields().type()).isEqualTo("wxpay");
        assertThat(result.checkoutSubmission().fields().signType()).isEqualTo("RSA");
        assertThat(result.checkoutSubmission().fields().keyVersion()).isNull();
        assertThat(output.getAll())
                .contains("event=liuhao_checkout_submission_created")
                .contains("requested_channel=wxpay")
                .contains("checkout_mode=form_post")
                .contains("method=post")
                .contains("action_class=liuhao_submit")
                .contains("out_trade_no_present=true")
                .contains("signed_fields_present=true")
                .contains("outcome=accepted")
                .contains("reason=VALIDATED")
                .doesNotContain(ORDER_ID)
                .doesNotContain(result.checkoutSubmission().fields().sign());
    }

    @Test
    void acceptsSignedJumpCreateResponseAndReturnsOnlyShortLivedCarrier() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(
                                ((MockClientHttpRequest) request).getBodyAsString())
                        .doesNotContain("channel_id="))
                .andRespond(withSuccess(
                        json(signed(createResponse(
                                "jump", "https://cashier.liuhao.net/pay/session-123"))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "alipay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("jump");
        assertThat(result.payInfo())
                .isEqualTo("https://cashier.liuhao.net/pay/session-123");
    }

    @Test
    void rejectsUnsupportedCreateCarriersAndUnsafeRedirects() throws Exception {
        for (String carrier : new String[] {"html", "scheme", "jsapi"}) {
            server.expect(requestTo(BASE_URL + "/api/pay/create"))
                    .andRespond(withSuccess(
                            json(signed(createResponse(
                                    carrier, "https://cashier.liuhao.net/pay/session-123"))),
                            MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.createPayment(createCommand()))
                    .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                            assertThat(exception)
                                    .extracting(MembershipPaymentException::code,
                                            MembershipPaymentException::providerTradeNo)
                                    .containsExactly(
                                            MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                                            TRADE_NO));
            server.reset();
        }
        for (String unsafeUrl : new String[] {
                "http://cashier.liuhao.net/pay/session-123",
                "/pay/session-123",
                "https://user:secret@cashier.liuhao.net/pay/session-123",
                "https://cashier.liuhao.net/pay/line\nbreak"
        }) {
            server.expect(requestTo(BASE_URL + "/api/pay/create"))
                    .andRespond(withSuccess(
                            json(signed(createResponse("jump", unsafeUrl))),
                            MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.createPayment(createCommand()))
                    .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                            assertThat(exception)
                                    .extracting(MembershipPaymentException::code,
                                            MembershipPaymentException::providerTradeNo)
                                    .containsExactly(
                                            MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                                            TRADE_NO));
            server.reset();
        }
    }

    @Test
    void submitsWxpayServerSideAndReturnsOnlyAConfirmedQrcodeRedirect(
            CapturedOutput output) throws Exception {
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    Map<String, String> form = formFields(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(form)
                            .containsEntry("method", "web")
                            .containsEntry("device", "pc")
                            .containsEntry("type", "wxpay")
                            .containsEntry("out_trade_no", ORDER_ID)
                            .containsEntry("clientip", "203.0.113.10")
                            .doesNotContainKey("channel_id");
                    String actualSignature = form.get("sign");
                    Map<String, String> unsigned = new LinkedHashMap<>(form);
                    unsigned.remove("sign");
                    unsigned.remove("sign_type");
                    assertThat(actualSignature).isEqualTo(signatures.sign(unsigned).get("sign"));
                })
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, "/pay/qrcode/" + TRADE_NO + "/"));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(formFields(
                                ((MockClientHttpRequest) request).getBodyAsString()))
                        .containsEntry("out_trade_no", ORDER_ID)
                        .doesNotContainKey("trade_no"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("qrcode");
        assertThat(result.payInfo()).isEqualTo(cashierUrl);
        assertThat(result.created()).isTrue();
        assertThat(output.getAll())
                .contains("event=liuhao_submit_checkout_resolution")
                .contains("requested_channel=wxpay")
                .contains("http_status_class=3xx")
                .contains("location_count_class=one")
                .contains("route_kind=qrcode_page_url")
                .contains("trade_no_present=true")
                .contains("query_locator=out_trade_no")
                .contains("query_outcome=verified")
                .contains("outcome=accepted")
                .contains("reason=QUERY_CONFIRMED_QRCODE_DERIVED")
                .doesNotContain(TRADE_NO)
                .doesNotContain(cashierUrl);
    }

    @Test
    void acceptsSeeOtherForTheSameConfirmedQrcodeRoute() throws Exception {
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.SEE_OTHER)
                        .header(HttpHeaders.LOCATION, cashierUrl));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("qrcode");
        assertThat(result.payInfo()).isEqualTo(cashierUrl);
    }

    @Test
    void derivesQrcodeFromConfirmedJspayRedirectWithoutReturningJspay(
            CapturedOutput output) throws Exception {
        String jspayUrl = BASE_URL + "/pay/jspay/" + TRADE_NO + "/";
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, jspayUrl));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("qrcode");
        assertThat(result.payInfo()).isEqualTo(cashierUrl);
        assertThat(output.getAll())
                .contains("event=liuhao_submit_checkout_resolution")
                .contains("route_kind=jspay_page_url")
                .contains("query_outcome=verified")
                .contains("outcome=accepted")
                .contains("reason=QUERY_CONFIRMED_QRCODE_DERIVED")
                .doesNotContain(TRADE_NO)
                .doesNotContain(jspayUrl);
    }

    @Test
    void acceptsTwoHundredWithoutLocationAfterSignedQueryConfirmsOrder(
            CapturedOutput output) throws Exception {
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "<!doctype html><html><body>六号微信收银台</body></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(formFields(
                                ((MockClientHttpRequest) request).getBodyAsString()))
                        .containsEntry("out_trade_no", ORDER_ID)
                        .doesNotContainKey("trade_no"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("qrcode");
        assertThat(result.payInfo()).isEqualTo(cashierUrl);
        assertThat(output.getAll())
                .contains("http_status_class=2xx")
                .contains("location_count_class=zero")
                .contains("route_kind=derived_qrcode_page_url")
                .contains("query_locator=out_trade_no")
                .contains("query_outcome=verified")
                .contains("reason=QUERY_CONFIRMED_QRCODE_DERIVED")
                .doesNotContain(TRADE_NO)
                .doesNotContain(cashierUrl)
                .doesNotContain("六号微信收银台");
        server.verify();
    }

    @Test
    void recoversExistingWxpayTradeByQueryWithoutSubmittingAgain() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(formFields(
                                ((MockClientHttpRequest) request).getBodyAsString()))
                        .containsEntry("trade_no", TRADE_NO)
                        .doesNotContainKey("out_trade_no"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.recoverPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"), TRADE_NO);

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("qrcode");
        assertThat(result.payInfo())
                .isEqualTo(BASE_URL + "/pay/qrcode/" + TRADE_NO + "/");
        server.verify();
    }

    @Test
    void recoversUnboundWxpayTradeByOutTradeNoWithoutSubmittingAgain() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(formFields(
                                ((MockClientHttpRequest) request).getBodyAsString()))
                        .containsEntry("out_trade_no", ORDER_ID)
                        .doesNotContainKey("trade_no"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.recoverPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"), null);

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerPayType()).isEqualTo("qrcode");
        assertThat(result.payInfo())
                .isEqualTo(BASE_URL + "/pay/qrcode/" + TRADE_NO + "/");
        server.verify();
    }

    @Test
    void recoverQueryTimeoutRemainsOutcomeUnknownWithoutSubmittingAgain() {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withException(new SocketTimeoutException(
                        "sensitive recovery timeout detail")));

        assertThatThrownBy(() -> client.recoverPayment(new PaymentCreateCommand(
                        ORDER_ID,
                        new java.math.BigDecimal("0.05"),
                        "wxpay",
                        "会员支付订单",
                        "203.0.113.10"), null))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception)
                                .extracting(MembershipPaymentException::code,
                                        MembershipPaymentException::providerTradeNo)
                                .containsExactly(
                                        MembershipPaymentErrorCode.LIUHAO_CREATE_OUTCOME_UNKNOWN,
                                        null));
        server.verify();
    }

    @Test
    void confirmedClosedWxpayTradeIsNotNavigableButKeepsRealTradeEvidence()
            throws Exception {
        Map<String, Object> closed = queryResponse(0, ORDER_ID, TRADE_NO);
        closed.put("trade_status", "TRADE_CLOSED");
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(closed)),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createPayment(new PaymentCreateCommand(
                        ORDER_ID,
                        new java.math.BigDecimal("0.05"),
                        "wxpay",
                        "会员支付订单",
                        "203.0.113.10")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception)
                                .extracting(MembershipPaymentException::code,
                                        MembershipPaymentException::providerTradeNo)
                                .containsExactly(
                                        MembershipPaymentErrorCode.LIUHAO_CHECKOUT_UNAVAILABLE,
                                        TRADE_NO));
        server.verify();
    }

    @Test
    void confirmedPaidWxpayTradeCanRestoreCanonicalQrcode() throws Exception {
        Map<String, Object> paid = queryResponse(0, ORDER_ID, TRADE_NO);
        paid.put("status", 1);
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withSuccess("<html></html>", MediaType.TEXT_HTML));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(paid)),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.payInfo())
                .isEqualTo(BASE_URL + "/pay/qrcode/" + TRADE_NO + "/");
        server.verify();
    }

    @Test
    void redirectTradeMustMatchSignedQueryTrade() throws Exception {
        String anotherTradeNo = "2026083108542370630";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION,
                                BASE_URL + "/pay/qrcode/" + anotherTradeNo + "/"));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createPayment(new PaymentCreateCommand(
                        ORDER_ID,
                        new java.math.BigDecimal("0.05"),
                        "wxpay",
                        "会员支付订单",
                        "203.0.113.10")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT));
        server.verify();
    }

    @Test
    void oauthRedirectCannotBeReturnedButConfirmedOrderStillUsesCanonicalQrcode(
            CapturedOutput output) throws Exception {
        String oauthUrl = "https://open.weixin.qq.com/connect/oauth2/authorize";
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, oauthUrl));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));

        assertThat(result.payInfo()).isEqualTo(cashierUrl);
        assertThat(output.getAll())
                .contains("event=liuhao_submit_checkout_resolution")
                .contains("route_kind=wechat_oauth_url")
                .contains("query_outcome=verified")
                .contains("outcome=accepted")
                .doesNotContain(oauthUrl);
        server.verify();
    }

    @Test
    void multipleRedirectLocationsUseSignedQueryAndCanonicalQrcode() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(
                                HttpHeaders.LOCATION,
                                BASE_URL + "/pay/qrcode/" + TRADE_NO + "/",
                                BASE_URL + "/pay/jspay/" + TRADE_NO + "/"));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.createPayment(new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "wxpay",
                "会员支付订单",
                "203.0.113.10"));
        assertThat(result.payInfo())
                .isEqualTo(BASE_URL + "/pay/qrcode/" + TRADE_NO + "/");
        server.verify();
    }

    @Test
    void submitTimeoutIsNotRetriedAndDoesNotExposeRedirectTradeEvidence(
            CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withException(new SocketTimeoutException(
                        "sensitive submit timeout detail")));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withException(new SocketTimeoutException(
                        "sensitive query timeout detail")));

        assertThatThrownBy(() -> client.createPayment(new PaymentCreateCommand(
                        ORDER_ID,
                        new java.math.BigDecimal("0.05"),
                        "wxpay",
                        "会员支付订单",
                        "203.0.113.10")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception)
                                .extracting(MembershipPaymentException::code,
                                        MembershipPaymentException::providerTradeNo)
                                .containsExactly(
                                        MembershipPaymentErrorCode.LIUHAO_CREATE_OUTCOME_UNKNOWN,
                                        null));
        server.verify();
        assertThat(output.getAll())
                .contains("event=liuhao_submit_checkout_resolution")
                .contains("query_outcome=unknown")
                .contains("outcome=uncertain")
                .contains("reason=QUERY_CONFIRMATION_FAILED")
                .doesNotContain("sensitive submit timeout detail");
        assertThat(output.getAll()).doesNotContain("sensitive query timeout detail");
    }

    @Test
    void queryForAnotherMerchantOrderIsRejectedAsConflictWithoutBindingRedirectTrade()
            throws Exception {
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, cashierUrl));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(
                                0,
                                "BaBVT8qWAQGYyiS9xjCEcg",
                                TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createPayment(new PaymentCreateCommand(
                        ORDER_ID,
                        new java.math.BigDecimal("0.05"),
                        "wxpay",
                        "会员支付订单",
                        "203.0.113.10")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception)
                                .extracting(MembershipPaymentException::code,
                                        MembershipPaymentException::providerTradeNo)
                                .containsExactly(
                                        MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT,
                                        null));
    }

    @Test
    void amountMismatchCannotBindTheRedirectCandidateAsLocalTradeEvidence() throws Exception {
        String cashierUrl = BASE_URL + "/pay/qrcode/" + TRADE_NO + "/";
        Map<String, Object> mismatchedAmount = queryResponse(0, ORDER_ID, TRADE_NO);
        mismatchedAmount.put("money", "0.06");
        server.expect(requestTo(BASE_URL + "/api/pay/submit"))
                .andRespond(withStatus(HttpStatus.FOUND)
                        .header(HttpHeaders.LOCATION, cashierUrl));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(mismatchedAmount)),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createPayment(new PaymentCreateCommand(
                        ORDER_ID,
                        new java.math.BigDecimal("0.05"),
                        "wxpay",
                        "会员支付订单",
                        "203.0.113.10")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception)
                                .extracting(MembershipPaymentException::code,
                                        MembershipPaymentException::providerTradeNo)
                                .containsExactly(
                                        MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT,
                                        null));
        server.verify();
    }

    @Test
    void rejectsCreateResponseForAnotherAmountOrPayType() throws Exception {
        Map<String, Object> response = createResponse(
                "jump", "https://cashier.liuhao.net/pay/session-123");
        response.put("money", "0.06");
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andRespond(withSuccess(json(signed(response)), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.createPayment(createCommand()))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(
                                MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT));
    }

    @Test
    void acceptsCodeZeroAndReturnsPlatformTradeNumberForPendingOrder() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.queryPayment(new PaymentQueryCommand(ORDER_ID, null));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.PENDING);
        assertThat(result.orderId()).isEqualTo(ORDER_ID);
        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
    }

    @Test
    void refundRequestLogsPreparedSignatureFieldsWithoutSecrets(
            CapturedOutput output) throws Exception {
        String expectedRefundNo = "RF-" + ORDER_ID;
        server.expect(requestTo(BASE_URL + "/api/pay/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    Map<String, String> form = formFields(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    assertThat(form)
                            .containsEntry("pid", PID)
                            .containsEntry("trade_no", TRADE_NO)
                            .containsEntry("money", "0.05")
                            .containsEntry("out_refund_no", expectedRefundNo)
                            .containsEntry("timestamp", Long.toString(NOW.getEpochSecond()))
                            .containsEntry("sign_type", "RSA")
                            .doesNotContainKey("out_trade_no");
                    assertThat(form.get("sign")).isNotBlank();
                })
                .andRespond(withSuccess(
                        json(signed(refundResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.refundPayment(new PaymentRefundCommand(
                ORDER_ID, TRADE_NO, new BigDecimal("0.05")));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.REFUNDED);
        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.providerRefundNo()).isEqualTo(expectedRefundNo);
        assertThat(result.refundedAmountYuan()).isEqualByComparingTo("0.05");
        assertThat(output.getAll())
                .contains("event=liuhao_request_signature")
                .contains("operation=refund")
                .contains("path=/api/pay/refund")
                .contains("pid_present=true")
                .contains("timestamp_present=true")
                .contains("trade_no_present=true")
                .contains("out_trade_no_present=false")
                .contains("money_present=true")
                .contains("out_refund_no_present=true")
                .contains("sign_type_present=true")
                .contains("sign_present=true")
                .contains("sign_type_class=rsa")
                .contains("signature_algorithm=SHA256WithRSA")
                .contains("request_stage=form_prepared")
                .contains("request_fields_valid=true")
                .contains("merchant_signature_self_check=verified")
                .contains("event=liuhao_response_verification")
                .contains("verification_outcome=verified")
                .contains("provider_code_numeric=0")
                .contains("provider_code_trust=verified")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO)
                .doesNotContain(expectedRefundNo)
                .doesNotContain("0.05")
                .doesNotContain("pid=" + PID);
    }

    @Test
    void unsignedRefundResponseCannotConfirmRefund(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("msg", "provider-sensitive-refund-message");
        server.expect(requestTo(BASE_URL + "/api/pay/refund"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refundPayment(new PaymentRefundCommand(
                ORDER_ID, TRADE_NO, new BigDecimal("0.05"))))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID));

        assertThat(output.getAll())
                .contains("event=liuhao_response_verification")
                .contains("operation=refund")
                .contains("provider_code_trust=untrusted")
                .contains("verification_stage=signature_metadata")
                .contains("reason=SIGN_TYPE_MISSING")
                .doesNotContain("provider-sensitive-refund-message")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO);
    }

    @Test
    void invalidPlatformSignatureCannotConfirmRefund(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = signed(refundResponse(0, ORDER_ID, TRADE_NO));
        response.put("sign", encoded("tampered".getBytes(StandardCharsets.UTF_8)));
        server.expect(requestTo(BASE_URL + "/api/pay/refund"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refundPayment(new PaymentRefundCommand(
                ORDER_ID, TRADE_NO, new BigDecimal("0.05"))))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID));

        assertThat(output.getAll())
                .contains("operation=refund")
                .contains("provider_code_trust=untrusted")
                .contains("verification_stage=rsa_verification")
                .contains("reason=PLATFORM_SIGNATURE_MISMATCH")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO);
    }

    @Test
    void signedRefundBusinessRejectionIsReportedForExplicitFailureClassification(
            CapturedOutput output) throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/refund"))
                .andRespond(withSuccess(
                        json(signed(refundResponse(1001, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.refundPayment(new PaymentRefundCommand(
                ORDER_ID, TRADE_NO, new BigDecimal("0.05"))))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID));

        assertThat(output.getAll())
                .contains("operation=refund")
                .contains("provider_code_numeric=1001")
                .contains("provider_code_trust=verified")
                .contains("verification_stage=business_code")
                .contains("reason=BUSINESS_CODE_REJECTED")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO);
    }

    @Test
    void closeAcknowledgementWithoutStatusQueriesBeforeReturningProviderState(
            CapturedOutput output) throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json(signed(closeResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        var result = client.closePayment(new PaymentCloseCommand(ORDER_ID, null));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.PENDING);
        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(output.getAll())
                .contains("locator_kind=out_trade_no")
                .contains("reason=CLOSE_ACK_STATUS_MISSING")
                .contains("reason=FOLLOWUP_QUERY_PENDING")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO);
    }

    @Test
    void explicitClosedStatusCanBeReturnedWithoutFollowupQuery() throws Exception {
        Map<String, Object> response = closeResponse(0, ORDER_ID, TRADE_NO);
        response.put("trade_status", "TRADE_CLOSED");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        json(signed(response)),
                        MediaType.APPLICATION_JSON));

        var result = client.closePayment(new PaymentCloseCommand(ORDER_ID, null));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.CLOSED);
        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
    }

    @Test
    void closeRequestLogsPreparedSignatureFieldsWithoutSecrets(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = closeResponse(0, ORDER_ID, TRADE_NO);
        response.put("trade_status", "TRADE_CLOSED");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String form = ((MockClientHttpRequest) request).getBodyAsString();
                    assertThat(form).contains("sign_type=RSA");
                    assertThat(form).containsPattern("(^|&)sign=[^&]+(&|$)");
                    assertThat(form).doesNotContain(
                            "merchant-private-key-is-held-by-signature-test-service");
                })
                .andRespond(withSuccess(
                        json(signed(response)), MediaType.APPLICATION_JSON));

        client.closePayment(new PaymentCloseCommand(ORDER_ID, TRADE_NO));

        assertThat(output.getAll())
                .contains("event=liuhao_request_signature")
                .contains("operation=close")
                .contains("path=/api/pay/close")
                .contains("pid_present=true")
                .contains("timestamp_present=true")
                .contains("trade_no_present=true")
                .contains("out_trade_no_present=false")
                .contains("sign_type_present=true")
                .contains("sign_present=true")
                .contains("sign_type_class=rsa")
                .contains("signature_algorithm=SHA256WithRSA")
                .contains("request_stage=form_prepared")
                .contains("request_fields_valid=true")
                .contains("merchant_signature_self_check=verified")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO)
                .doesNotContain("pid=" + PID)
                .doesNotContain("merchant-private-key-is-held-by-signature-test-service");
    }

    @Test
    void unsignedCloseResponseLogsBoundedProviderCodeAsUntrusted(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 1001);
        response.put("msg", "provider-sensitive-message-1001");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID));

        assertThat(output.getAll())
                .contains("event=liuhao_response_verification")
                .contains("operation=close")
                .contains("has_timestamp=false")
                .contains("has_sign=false")
                .contains("has_sign_type=false")
                .contains("provider_code_numeric=1001")
                .contains("provider_code_trust=untrusted")
                .contains("verification_stage=signature_metadata")
                .contains("reason=SIGN_TYPE_MISSING")
                .doesNotContain("provider-sensitive-message-1001")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO)
                .doesNotContain("pid=" + PID);
    }

    @Test
    void rejectsSignedNonZeroProviderCodeWithoutClosingOrder(
            CapturedOutput output) throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(
                        json(signed(closeResponse(2, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID));
        assertThat(meterRegistry.get("membership_payment_provider_failure_total")
                        .tags(
                                "provider", "liuhao",
                                "operation", "close",
                                "reason", "liuhao_response_invalid")
                        .counter()
                        .count())
                .isEqualTo(1.0d);
        assertThat(output.getAll())
                .contains("verification_stage=business_code")
                .contains("reason=BUSINESS_CODE_REJECTED")
                .contains("reason=CLOSE_BUSINESS_CODE_REJECTED")
                .contains("provider_code_numeric=2")
                .contains("provider_code_trust=verified");
    }

    @Test
    void staleTimestampIsClassifiedAsResponseInvalidRatherThanBusinessRejection(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = closeResponse(0, ORDER_ID, TRADE_NO);
        response.put("timestamp", Long.toString(NOW.minus(Duration.ofHours(1)).getEpochSecond()));
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(signed(response)), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_RESPONSE_INVALID));

        assertThat(output.getAll())
                .contains("verification_stage=timestamp_validation")
                .contains("reason=TIMESTAMP_INVALID")
                .contains("reason=CLOSE_RESPONSE_INVALID")
                .doesNotContain("reason=CLOSE_BUSINESS_CODE_REJECTED");
    }

    @Test
    void rejectsInvalidPlatformSignatureWithoutClosingOrder(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = signed(closeResponse(0, ORDER_ID, TRADE_NO));
        response.put("sign", Base64.getEncoder().encodeToString("tampered".getBytes(StandardCharsets.UTF_8)));
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_SIGNATURE_INVALID));
        assertThat(output.getAll())
                .contains("provider_code_numeric=0")
                .contains("provider_code_trust=untrusted")
                .contains("reason=PLATFORM_SIGNATURE_MISMATCH");
    }

    @Test
    void closeResponseWithoutSignatureReportsSignatureMetadataLayer(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = commonResponse(0);
        response.put("msg", "provider-sensitive-message-7c39");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOf(MembershipPaymentException.class);

        assertThat(output.getAll())
                .contains("event=liuhao_response_verification")
                .contains("operation=close")
                .contains("verification_stage=signature_metadata")
                .contains("reason=SIGN_MISSING")
                .contains("provider_code=untrusted")
                .doesNotContain(ORDER_ID)
                .doesNotContain("provider-sensitive-message-7c39");
    }

    @Test
    void closeResponseWithMalformedBase64ReportsEncodingLayer(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = commonResponse(0);
        response.put("sign", "not-base64%%%");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOf(MembershipPaymentException.class);

        assertThat(output.getAll())
                .contains("verification_stage=signature_encoding")
                .contains("reason=SIGN_BASE64_INVALID")
                .doesNotContain("not-base64%%%");
    }

    @Test
    void closeResponseWithWrongRsaSignatureReportsCryptoLayer(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = commonResponse(0);
        response.put("sign", Base64.getEncoder().encodeToString(
                "wrong".getBytes(StandardCharsets.UTF_8)));
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOf(MembershipPaymentException.class);

        assertThat(output.getAll())
                .contains("verification_stage=rsa_verification")
                .contains("reason=PLATFORM_SIGNATURE_MISMATCH")
                .doesNotContain(response.get("sign").toString());
    }

    @Test
    void successfulCloseVerificationLogsOnlyNormalizedMetadata(
            CapturedOutput output) throws Exception {
        Map<String, Object> close = closeResponse(0, ORDER_ID, TRADE_NO);
        close.put("msg", "provider-sensitive-message-8d42");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(signed(close)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        client.closePayment(new PaymentCloseCommand(ORDER_ID, null));

        assertThat(output.getAll())
                .contains("verification_stage=complete")
                .contains("verification_outcome=verified")
                .contains("provider_code=0")
                .contains("has_trade_no=true")
                .contains("has_out_trade_no=true")
                .doesNotContain(ORDER_ID)
                .doesNotContain(TRADE_NO)
                .doesNotContain("provider-sensitive-message-8d42");
    }

    @Test
    void unexpectedResponseFieldIsReducedToBooleanWithoutLoggingItsName(
            CapturedOutput output) throws Exception {
        Map<String, Object> response = commonResponse(0);
        response.put("provider_secret_field_92", "provider-sensitive-value-92");
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(json(response), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOf(MembershipPaymentException.class);

        assertThat(output.getAll())
                .contains("unexpected_field_present=true")
                .doesNotContain("provider_secret_field_92")
                .doesNotContain("provider-sensitive-value-92");
    }

    @Test
    void malformedJsonReportsJsonShapeLayerWithoutLoggingBody(
            CapturedOutput output) {
        String malformed = "{provider-sensitive-malformed-json";
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withSuccess(malformed, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOf(MembershipPaymentException.class);

        assertThat(output.getAll())
                .contains("verification_stage=json_shape")
                .contains("reason=JSON_UNREADABLE")
                .doesNotContain(malformed);
    }

    @Test
    void rejectsQueryResponseForAnotherMerchantOrder() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, "BaBVT8qWAQGYyiS9xjCEcg", TRADE_NO))),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.queryPayment(new PaymentQueryCommand(ORDER_ID, null)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT));
    }

    @Test
    void rejectsQueryResponseForAnotherPlatformTradeNumber() throws Exception {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withSuccess(
                        json(signed(queryResponse(0, ORDER_ID, "2026083108542370630"))),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.queryPayment(
                        new PaymentQueryCommand(ORDER_ID, TRADE_NO)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.LIUHAO_ORDER_CONFLICT));
    }

    @Test
    void mapsSocketTimeoutWithoutClosingOrderOrLeakingDetails(
            CapturedOutput output) {
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withException(new SocketTimeoutException("sensitive upstream detail")));

        assertThatThrownBy(() -> client.closePayment(new PaymentCloseCommand(ORDER_ID, null)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(MembershipPaymentErrorCode.LIUHAO_TIMEOUT);
                    assertThat(exception.getMessage()).doesNotContain("sensitive upstream detail");
                });
        assertThat(output.getAll())
                .contains("verification_stage=transport")
                .contains("reason=TRANSPORT_TIMEOUT")
                .doesNotContain("sensitive upstream detail");
    }

    private Map<String, Object> queryResponse(
            int code,
            String orderId,
            String tradeNo) {
        Map<String, Object> response = commonResponse(code);
        response.put("out_trade_no", orderId);
        response.put("trade_no", tradeNo);
        response.put("status", 0);
        response.put("money", "0.05");
        return response;
    }

    private Map<String, Object> createResponse(String payType, String payInfo) {
        Map<String, Object> response = commonResponse(0);
        response.put("out_trade_no", ORDER_ID);
        response.put("trade_no", TRADE_NO);
        response.put("type", "alipay");
        response.put("money", "0.05");
        response.put("pay_type", payType);
        response.put("pay_info", payInfo);
        return response;
    }

    private PaymentCreateCommand createCommand() {
        return new PaymentCreateCommand(
                ORDER_ID,
                new java.math.BigDecimal("0.05"),
                "alipay",
                "会员支付订单",
                "203.0.113.10");
    }

    private Map<String, Object> closeResponse(
            int code,
            String orderId,
            String tradeNo) {
        Map<String, Object> response = commonResponse(code);
        response.put("out_trade_no", orderId);
        response.put("trade_no", tradeNo);
        return response;
    }

    private Map<String, Object> refundResponse(
            int code,
            String orderId,
            String tradeNo) {
        Map<String, Object> response = commonResponse(code);
        response.put("out_trade_no", orderId);
        response.put("trade_no", tradeNo);
        response.put("out_refund_no", "RF-" + orderId);
        response.put("money", "0.05");
        return response;
    }

    private Map<String, Object> commonResponse(int code) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", code);
        response.put("msg", code == 0 ? "success" : "rejected");
        response.put("pid", PID);
        response.put("timestamp", Long.toString(NOW.getEpochSecond()));
        response.put("sign_type", "RSA");
        return response;
    }

    private Map<String, Object> signed(Map<String, Object> response) throws Exception {
        Map<String, Object> signed = new LinkedHashMap<>(response);
        Signature signer = Signature.getInstance("SHA256WithRSA");
        signer.initSign(platform.getPrivate());
        signer.update(signatures.canonicalize(signed).getBytes(StandardCharsets.UTF_8));
        signed.put("sign", Base64.getEncoder().encodeToString(signer.sign()));
        return signed;
    }

    private static Map<String, String> formFields(String encodedForm) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : encodedForm.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(
                    URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values;
    }

    private String json(Map<String, Object> response) throws Exception {
        return objectMapper.writeValueAsString(response);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
