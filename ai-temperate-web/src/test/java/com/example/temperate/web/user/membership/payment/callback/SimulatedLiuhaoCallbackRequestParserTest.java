package com.example.temperate.web.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 该单元测试是来约束 GET、POST 表单和 POST JSON 归一化等价，并拒绝重复键、混合参数源、未知字段及非字符串 JSON。
 */
final class SimulatedLiuhaoCallbackRequestParserTest {

    private SimulatedLiuhaoCallbackRequestParser parser;

    @BeforeEach
    void setUp() {
        parser = new SimulatedLiuhaoCallbackRequestParser(
                new ObjectMapper(), properties());
    }

    @Test
    void getFormAndJsonProduceTheSameCommand() throws Exception {
        Map<String, String> fields = fields();
        String encoded = form(fields);

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/notify");
        get.setQueryString(encoded);
        MockHttpServletRequest form = new MockHttpServletRequest("POST", "/notify");
        form.setContentType("application/x-www-form-urlencoded;charset=UTF-8");
        form.setContent(encoded.getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/notify");
        json.setContentType("application/json;charset=UTF-8");
        json.setContent(new ObjectMapper().writeValueAsBytes(fields));

        SimulatedLiuhaoCallbackCommand getCommand = parser.parse(get);
        assertThat(parser.parse(form)).isEqualTo(getCommand);
        assertThat(parser.parse(json)).isEqualTo(getCommand);
    }

    @Test
    void duplicateGetScalarAndPostQueryAreRejected() {
        MockHttpServletRequest duplicate = new MockHttpServletRequest("GET", "/notify");
        duplicate.setQueryString(form(fields()) + "&pid=second");
        MockHttpServletRequest mixed = new MockHttpServletRequest("POST", "/notify");
        mixed.setQueryString("pid=merchant-test");
        mixed.setContentType("application/json");
        mixed.setContent("{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(duplicate))
                .isInstanceOf(SimulatedPaymentCallbackTransportException.class);
        assertThatThrownBy(() -> parser.parse(mixed))
                .isInstanceOf(SimulatedPaymentCallbackTransportException.class);
    }

    @Test
    void duplicateUnknownAndNonStringJsonFieldsAreRejected() {
        assertInvalidJson("{\"pid\":\"a\",\"pid\":\"b\"}");
        assertInvalidJson("{\"unknown\":\"value\"}");
        assertInvalidJson("{\"pid\":17}");
    }

    @Test
    void oversizedBodiesGetBodiesAndNonUtf8PostsAreRejectedAtTransportBoundary() {
        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/notify");
        oversized.setContentType("application/json;charset=UTF-8");
        oversized.setContent(new byte[16_385]);
        MockHttpServletRequest getWithBody = new MockHttpServletRequest("GET", "/notify");
        getWithBody.setQueryString(form(fields()));
        getWithBody.setContent("forbidden".getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest nonUtf8 = new MockHttpServletRequest("POST", "/notify");
        nonUtf8.setContentType("application/json;charset=ISO-8859-1");
        nonUtf8.setContent("{}".getBytes(StandardCharsets.ISO_8859_1));

        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOfSatisfying(
                        SimulatedPaymentCallbackTransportException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(
                                SimulatedPaymentCallbackTransportException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> parser.parse(getWithBody))
                .isInstanceOfSatisfying(
                        SimulatedPaymentCallbackTransportException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(
                                SimulatedPaymentCallbackTransportException.Kind.BAD_REQUEST));
        assertThatThrownBy(() -> parser.parse(nonUtf8))
                .isInstanceOfSatisfying(
                        SimulatedPaymentCallbackTransportException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(
                                SimulatedPaymentCallbackTransportException.Kind.UNSUPPORTED_MEDIA_TYPE));
    }

    private void assertInvalidJson(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/notify");
        request.setContentType("application/json");
        request.setContent(value.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> parser.parse(request))
                .isInstanceOf(SimulatedPaymentCallbackTransportException.class);
    }

    private static Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pid", "merchant-test");
        fields.put("trade_no", "provider-trade-1");
        fields.put("out_trade_no", "AaAjECcaAQGqi_h2Rl1PiA");
        fields.put("api_trade_no", "channel-trade-1");
        fields.put("type", "alipay");
        fields.put("trade_status", "TRADE_SUCCESS");
        fields.put("addtime", "2026-08-20 11:59:50");
        fields.put("endtime", "2026-08-20 11:59:55");
        fields.put("name", "PLUS membership");
        fields.put("money", "20.00");
        fields.put("param", "");
        fields.put("buyer", "");
        fields.put("timestamp", "1787227200");
        fields.put("sign", "simulated-signature");
        fields.put("sign_type", "RSA");
        return fields;
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        true,
                        "merchant-test",
                        "0123456789abcdef0123456789abcdef",
                        Duration.ofMinutes(5),
                        16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30),
                        3));
    }
}
