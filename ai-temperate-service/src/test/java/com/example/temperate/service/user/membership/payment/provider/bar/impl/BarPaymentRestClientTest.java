package com.example.temperate.service.user.membership.payment.provider.bar.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.example.temperate.model.user.membership.payment.PaymentProviderType;
import com.example.temperate.service.user.membership.payment.MembershipPaymentErrorCode;
import com.example.temperate.service.user.membership.payment.MembershipPaymentException;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentProviderStatus;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 该测试是来固定 BAR RestClient 的四条真实 HTTP 路径、响应验签、409 反查和传输错误分类；测试不连接公网。
 */
class BarPaymentRestClientTest {

    private static final String BASE_URL = "https://ihaveagoddamnplan.com";
    private static final String ORDER_ID = "AaAjECcaAQGqi_h2Rl1PiA";
    private static final String TRADE_NO = "1234567890123456789";
    private static final String API_TRADE_NO = "BAR-P-1234567890123456790";
    private static final String API_KEY =
            "bar_sk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final Instant NOW = Instant.parse("2026-08-21T16:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BarPaymentSignatureService signatures =
            new BarPaymentSignatureServiceImpl(Map.of(1, API_KEY));
    private MockRestServiceServer server;
    private BarPaymentRestClientImpl client;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(NOW, ZoneOffset.UTC);
        client = new BarPaymentRestClientImpl(
                builder.build(),
                objectMapper,
                signatures,
                barProperties(),
                clock);
    }

    @Test
    void createsCheckoutAndReturnsFreshSignedBrowserSubmission() {
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(json(expectedCheckoutRequest(NOW))))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(createResponse(true)))));

        var result = client.createCheckout(new PaymentCheckoutCommand(
                ORDER_ID,
                new BigDecimal("20.00"),
                "alipay",
                "会员模拟支付订单"));

        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.expiresAt()).hasToString("2026-08-21T16:15Z");
        assertThat(result.created()).isTrue();
        assertThat(result.checkoutSubmission().provider()).isEqualTo(PaymentProviderType.BAR);
        assertThat(result.checkoutSubmission().action())
                .hasToString(BASE_URL + "/api/pay/submit");
        assertThat(result.checkoutSubmission().method()).isEqualTo("POST");
        assertThat(result.checkoutSubmission().contentType())
                .isEqualTo("application/x-www-form-urlencoded");
        assertThat(result.checkoutSubmission().submitExpiresAt())
                .hasToString("2026-08-21T16:05Z");
        var fields = result.checkoutSubmission().fields();
        assertThat(fields.pid()).isEqualTo("1001");
        assertThat(fields.outTradeNo()).isEqualTo(ORDER_ID);
        assertThat(fields.type()).isEqualTo("alipay");
        assertThat(fields.name()).isEqualTo("会员模拟支付订单");
        assertThat(fields.money()).isEqualTo("20.00");
        assertThat(fields.notifyUrl())
                .isEqualTo("https://niko000o.site/api/payment/bar/notify");
        assertThat(fields.returnUrl()).isEqualTo("https://niko000o.site/payment/result");
        assertThat(fields.timestamp()).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(fields.keyVersion()).isEqualTo("1");
        assertThat(fields.signType()).isEqualTo("HMAC-SHA256");
        assertThat(result.checkoutSubmission().fields().sign())
                .matches("^[0-9a-f]{64}$");
        assertThat(result.checkoutSubmission().fields().toString())
                .doesNotContain(API_KEY, "bar_checkout_session", "#token");
    }

    @Test
    void acceptsIdempotentCreateWithoutLegacyRedirectFields() {
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(createResponse(false)))));

        var result = client.createCheckout(new PaymentCheckoutCommand(
                ORDER_ID,
                new BigDecimal("20.00"),
                "alipay",
                "会员模拟支付订单"));

        assertThat(result.created()).isFalse();
        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.checkoutSubmission()).isNotNull();
    }

    @Test
    void idempotentReplayRegeneratesSubmitTimestampAndSignatureAfterClockAdvances() {
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(createResponse(false)))));
        var first = client.createCheckout(new PaymentCheckoutCommand(
                ORDER_ID,
                new BigDecimal("20.00"),
                "alipay",
                "会员模拟支付订单"));

        clock.advance(Duration.ofSeconds(2));
        Map<String, Object> replayResponse = createResponse(false);
        replayResponse.put("timestamp", Long.toString(clock.instant().getEpochSecond()));
        startNextExchange();
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(replayResponse))));
        var replay = client.createCheckout(new PaymentCheckoutCommand(
                ORDER_ID,
                new BigDecimal("20.00"),
                "alipay",
                "会员模拟支付订单"));

        assertThat(replay.providerTradeNo()).isEqualTo(first.providerTradeNo());
        assertThat(replay.checkoutSubmission().fields().timestamp())
                .isNotEqualTo(first.checkoutSubmission().fields().timestamp());
        assertThat(replay.checkoutSubmission().fields().sign())
                .isNotEqualTo(first.checkoutSubmission().fields().sign());
    }

    @Test
    void rejectsLegacyRedirectFieldsAsUnknownCreateResponseData() {
        Map<String, Object> legacy = createResponse(true);
        legacy.put("pay_type", "jump");
        legacy.put("pay_url", BASE_URL + "/pay/" + TRADE_NO + "#token=test-token");
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(legacy))));

        assertThatThrownBy(() -> client.createCheckout(new PaymentCheckoutCommand(
                        ORDER_ID,
                        new BigDecimal("20.00"),
                        "alipay",
                        "会员模拟支付订单")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.BAR_RESPONSE_INVALID));
    }

    @Test
    void rejectsMismatchedCreateFacts() {
        Map<String, Object> invalidTradeNo = createResponse(true);
        invalidTradeNo.put("trade_no", "BAR-123");
        expectCreateFailure(
                invalidTradeNo,
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_RESPONSE_INVALID);

        Map<String, Object> wrongOrder = createResponse(true);
        wrongOrder.put("out_trade_no", "BaAjECcaAQGqi_h2Rl1PiA");
        expectCreateFailure(
                wrongOrder,
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_ORDER_CONFLICT);

        Map<String, Object> expired = createResponse(true);
        expired.put("expires_at", "2026-08-21T16:00:00Z");
        expectCreateFailure(
                expired,
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_RESPONSE_INVALID);

        expectCreateFailure(
                createResponse(false),
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_RESPONSE_INVALID);
    }

    @Test
    void rejectsInvalidCreateSignatureUnknownKeyAndStaleTimestamp() {
        Map<String, Object> invalidSignature = signed(createResponse(true));
        invalidSignature.put("sign", "0".repeat(64));
        expectSignedCreateFailure(
                invalidSignature,
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_SIGNATURE_INVALID);

        Map<String, Object> unknownKey = signed(createResponse(true));
        unknownKey.put("key_version", 2);
        expectSignedCreateFailure(
                unknownKey,
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_AUTH_FAILED);

        Map<String, Object> stale = createResponse(true);
        stale.put("timestamp", Long.toString(NOW.minus(Duration.ofMinutes(6)).getEpochSecond()));
        expectCreateFailure(
                stale,
                HttpStatus.CREATED,
                MembershipPaymentErrorCode.BAR_RESPONSE_INVALID);
    }

    @Test
    void queriesSignedPaidFactAndKeepsTradeNumbersAsStrings() {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(queryResponse("PAID")))));

        var result = client.queryPayment(new PaymentQueryCommand(ORDER_ID, TRADE_NO));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.PAID);
        assertThat(result.providerTradeNo()).isEqualTo(TRADE_NO);
        assertThat(result.channelTradeNo()).isEqualTo(API_TRADE_NO);
        assertThat(result.amountYuan()).isEqualByComparingTo("20.00");
    }

    @Test
    void closeConflictImmediatelyQueriesAuthoritativePaidState() {
        server.expect(requestTo(BASE_URL + "/api/pay/close"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":40901,\"msg\":\"hidden\",\"data\":null}"));
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(queryResponse("PAID")))));

        var result = client.closePayment(new PaymentCloseCommand(ORDER_ID, TRADE_NO));

        assertThat(result.status()).isEqualTo(PaymentProviderStatus.PAID);
    }

    @Test
    void sendsFullRefundAndRejectsTamperedSignedResponse() {
        Map<String, Object> refund = queryResponse("REFUNDED");
        refund.put("status", 2);
        refund.put("refund_no", "BAR-R-1234567890123456791");
        refund.put("refund_amount", "20.00");
        server.expect(requestTo(BASE_URL + "/api/pay/refund"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(signed(refund))));

        var result = client.refundPayment(new PaymentRefundCommand(
                ORDER_ID, TRADE_NO, new BigDecimal("20.00")));
        assertThat(result.status()).isEqualTo(PaymentProviderStatus.REFUNDED);

        Map<String, Object> tampered = signed(queryResponse("PAID"));
        tampered.put("money", "21.00");
        startNextExchange();
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(tampered)));
        assertThatThrownBy(() -> client.queryPayment(
                        new PaymentQueryCommand(ORDER_ID, TRADE_NO)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code())
                                .isEqualTo(MembershipPaymentErrorCode.BAR_SIGNATURE_INVALID));
    }

    @Test
    void mapsSocketTimeoutWithoutLeakingUpstreamDetails() {
        server.expect(requestTo(BASE_URL + "/api/pay/query"))
                .andRespond(withException(new SocketTimeoutException("sensitive detail")));

        assertThatThrownBy(() -> client.queryPayment(
                        new PaymentQueryCommand(ORDER_ID, TRADE_NO)))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(MembershipPaymentErrorCode.BAR_TIMEOUT);
                    assertThat(exception.getMessage()).doesNotContain("sensitive detail");
                });
    }

    private Map<String, Object> createResponse(boolean created) {
        Map<String, Object> response = commonResponse();
        response.put("trade_no", TRADE_NO);
        response.put("out_trade_no", ORDER_ID);
        response.put("expires_at", "2026-08-21T16:15:00Z");
        response.put("created", created);
        return response;
    }

    private Map<String, Object> queryResponse(String status) {
        Map<String, Object> response = commonResponse();
        response.put("pid", 1001);
        response.put("trade_no", TRADE_NO);
        response.put("out_trade_no", ORDER_ID);
        response.put("status", "PENDING".equals(status) ? 0 : 1);
        response.put("trade_status", status);
        response.put("notify_status", "SUCCESS");
        response.put("money", "20.00");
        response.put("created_at", "2026-08-21T16:00:00Z");
        response.put("finished_at", "PENDING".equals(status)
                ? ""
                : "2026-08-21T16:02:00Z");
        response.put("api_trade_no", "PENDING".equals(status) ? "" : API_TRADE_NO);
        return response;
    }

    private Map<String, Object> commonResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", Long.toString(NOW.getEpochSecond()));
        response.put("key_version", 1);
        response.put("sign_type", "HMAC-SHA256");
        return response;
    }

    private Map<String, Object> signed(Map<String, Object> response) {
        Map<String, Object> signed = new LinkedHashMap<>(response);
        signed.put("sign", signatures.sign(response, 1).get("sign"));
        return signed;
    }

    private Map<String, String> expectedCheckoutRequest(Instant signedAt) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("pid", "1001");
        fields.put("timestamp", Long.toString(signedAt.getEpochSecond()));
        fields.put("key_version", "1");
        fields.put("sign_type", "HMAC-SHA256");
        fields.put("out_trade_no", ORDER_ID);
        fields.put("type", "alipay");
        fields.put("name", "会员模拟支付订单");
        fields.put("money", "20.00");
        fields.put("notify_url", "https://niko000o.site/api/payment/bar/notify");
        fields.put("return_url", "https://niko000o.site/payment/result");
        return signatures.sign(fields, 1);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }

    private void expectCreateFailure(
            Map<String, Object> response,
            HttpStatus status,
            MembershipPaymentErrorCode expectedCode) {
        expectSignedCreateFailure(signed(response), status, expectedCode);
    }

    private void expectSignedCreateFailure(
            Map<String, Object> response,
            HttpStatus status,
            MembershipPaymentErrorCode expectedCode) {
        startNextExchange();
        server.expect(requestTo(BASE_URL + "/api/pay/create"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json(response)));
        assertThatThrownBy(() -> client.createCheckout(new PaymentCheckoutCommand(
                        ORDER_ID,
                        new BigDecimal("20.00"),
                        "alipay",
                        "会员模拟支付订单")))
                .isInstanceOfSatisfying(MembershipPaymentException.class, exception ->
                        assertThat(exception.code()).isEqualTo(expectedCode));
    }

    /**
     * 每个失败样本都先验证并清空上一轮交换；Spring 测试服务器在首个真实请求后会冻结期望集合，
     * 因此不能在同一轮中动态追加下一条期望。
     */
    private void startNextExchange() {
        server.verify();
        server.reset();
    }

    private static MembershipPaymentProperties.Bar barProperties() {
        return new MembershipPaymentProperties.Bar(
                true,
                URI.create(BASE_URL),
                "1001",
                1,
                Map.of(1, API_KEY),
                URI.create("https://niko000o.site/api/payment/bar/notify"),
                URI.create("https://niko000o.site/payment/result"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                65_536);
    }

    /** 该可变时钟是来在不等待真实时间的情况下验证幂等重放会重新生成 submit 签名。 */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
