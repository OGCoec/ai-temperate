package com.example.temperate.web.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.temperate.service.user.membership.payment.callback.PaymentCallbackReceiveService;
import com.example.temperate.service.user.membership.payment.callback.SimulatedLiuhaoCallbackCommand;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.observability.MembershipPaymentMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 该 Web 契约测试是来约束双协议回调的精确 success/fail、UTF-8 纯文本、无缓存头和 401/415 状态。
 */
final class SimulatedLiuhaoPaymentCallbackControllerTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private PaymentCallbackReceiveService receiveService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MembershipPaymentProperties properties = properties();
        receiveService = mock(PaymentCallbackReceiveService.class);
        SimulatedLiuhaoPaymentCallbackController controller =
                new SimulatedLiuhaoPaymentCallbackController(
                        new SimulatedPaymentCallbackKeyVerifier(properties),
                        new SimulatedLiuhaoCallbackRequestParser(
                                new ObjectMapper(), properties),
                        receiveService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new SimulatedPaymentCallbackExceptionHandler(
                        mock(MembershipPaymentMetrics.class)))
                .build();
    }

    @Test
    void getSuccessIsExactPlainTextAndNoStore() throws Exception {
        mockMvc.perform(get(URI.create(
                        SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH
                                + "?" + form(fields())))
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY))
                .andExpect(status().isOk())
                .andExpect(content().string("success"))
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("CDN-Cache-Control", "no-store"));
        verify(receiveService).receive(any());
    }

    @Test
    void postJsonUsesTheSameSuccessContract() throws Exception {
        mockMvc.perform(post(SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH)
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(fields())))
                .andExpect(status().isOk())
                .andExpect(content().string("success"))
                .andExpect(content().contentType("text/plain;charset=UTF-8"));
    }

    @Test
    void postFormUsesTheSameSuccessContract() throws Exception {
        mockMvc.perform(post(SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH)
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(fields())))
                .andExpect(status().isOk())
                .andExpect(content().string("success"))
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("CDN-Cache-Control", "no-store"));
    }

    @Test
    void getAndPostJsonDeliverTheSameNormalizedCallbackCommand() throws Exception {
        mockMvc.perform(get(URI.create(
                        SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH
                                + "?" + form(fields())))
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY))
                .andExpect(status().isOk());
        mockMvc.perform(post(SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH)
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(fields())))
                .andExpect(status().isOk());

        ArgumentCaptor<SimulatedLiuhaoCallbackCommand> commandCaptor =
                ArgumentCaptor.forClass(SimulatedLiuhaoCallbackCommand.class);
        verify(receiveService, times(2)).receive(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues())
                .hasSize(2)
                .allSatisfy(command -> assertThat(command)
                        .isEqualTo(commandCaptor.getAllValues().getFirst()));
    }

    @Test
    void wrongKeyReturns401AndExactFail() throws Exception {
        mockMvc.perform(get(SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("fail"))
                .andExpect(content().contentType("text/plain;charset=UTF-8"));
    }

    @Test
    void unsupportedPostMediaTypeReturns415AndExactFail() throws Exception {
        mockMvc.perform(post(SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH)
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY)
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<callback/>") )
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().string("fail"));
    }

    @Test
    void unexpectedCallbackFailureStillReturnsExactPlainTextFail() throws Exception {
        doThrow(new IllegalStateException("test failure"))
                .when(receiveService).receive(any());

        mockMvc.perform(get(URI.create(
                        SimulatedLiuhaoPaymentCallbackController.CALLBACK_PATH
                                + "?" + form(fields())))
                        .header(
                                SimulatedLiuhaoPaymentCallbackController.CALLBACK_KEY_HEADER,
                                KEY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("fail"))
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("CDN-Cache-Control", "no-store"));
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
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static MembershipPaymentProperties properties() {
        return new MembershipPaymentProperties(
                true,
                Duration.ofMinutes(5),
                Duration.ofMinutes(5),
                new MembershipPaymentProperties.Simulator(
                        true, "merchant-test", KEY, Duration.ofMinutes(5), 16_384, false),
                new MembershipPaymentProperties.Callback(
                        5_000L, 100, 20, Duration.ofSeconds(60),
                        Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofHours(6)),
                new MembershipPaymentProperties.OrderPersist(
                        5_000L, 100, 20, Duration.ofSeconds(60), Duration.ofMillis(100)),
                new MembershipPaymentProperties.Rabbit(
                        List.of(10_000L, 10_000L, 10_000L, 15_000L, 15_000L,
                                30_000L, 30_000L, 60_000L, 120_000L),
                        List.of(30_000L, 30_000L, 60_000L, 60_000L, 120_000L),
                        Duration.ofSeconds(30), 3));
    }
}
