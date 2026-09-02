package com.example.temperate.web.user.membership.payment.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.temperate.service.user.membership.payment.callback.LiuhaoPaymentCallbackService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** 该测试是来固定六号回调接收有界单值完整参数集，并只在服务处理成功后返回纯文本 success。 */
class LiuhaoPaymentCallbackControllerTest {

    @Test
    void exposesTheProductionCallbackAsGetOnly() throws NoSuchMethodException {
        assertThat(LiuhaoPaymentCallbackController.class
                        .getDeclaredMethod("notify", MultiValueMap.class)
                        .getAnnotation(GetMapping.class))
                .isNotNull();
        assertThat(Arrays.stream(LiuhaoPaymentCallbackController.class.getDeclaredMethods())
                        .noneMatch(method -> method.isAnnotationPresent(PostMapping.class)))
                .isTrue();
    }

    @Test
    void forwardsOfficialAndFutureSignedFieldsBeforeReturningExactSuccess() {
        LiuhaoPaymentCallbackService service = mock(LiuhaoPaymentCallbackService.class);
        LiuhaoPaymentCallbackController controller =
                new LiuhaoPaymentCallbackController(service);
        LinkedMultiValueMap<String, String> parameters = parameters();
        Map<String, String> expected = singleValues(parameters);

        ResponseEntity<String> response = controller.notify(parameters);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("success");
        assertThat(response.getHeaders().getContentType())
                .hasToString("text/plain;charset=UTF-8");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("CDN-Cache-Control")).isEqualTo("no-store");
        verify(service).receive(argThat(command -> command.externalFields().equals(expected)
                && "channel-trade-1".equals(command.externalFields().get("api_trade_no"))
                && "future-value".equals(command.externalFields().get("future_flag"))));
    }

    @Test
    void preservesEmptyOptionalFieldForTheSignatureLayer() {
        LiuhaoPaymentCallbackService service = mock(LiuhaoPaymentCallbackService.class);
        LiuhaoPaymentCallbackController controller =
                new LiuhaoPaymentCallbackController(service);
        LinkedMultiValueMap<String, String> parameters = parameters();
        parameters.set("param", "");

        controller.notify(parameters);

        verify(service).receive(argThat(command ->
                command.externalFields().containsKey("param")
                        && command.externalFields().get("param").isEmpty()));
    }

    @Test
    void returnsSuccessForAnIdempotentDuplicateAcceptedByTheService() {
        LiuhaoPaymentCallbackService service = mock(LiuhaoPaymentCallbackService.class);
        when(service.receive(any())).thenReturn(true);
        LiuhaoPaymentCallbackController controller =
                new LiuhaoPaymentCallbackController(service);

        ResponseEntity<String> response = controller.notify(parameters());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("success");
    }

    @Test
    void rejectsRepeatedParameterBeforeCallingTheService() {
        LiuhaoPaymentCallbackService service = mock(LiuhaoPaymentCallbackService.class);
        LiuhaoPaymentCallbackController controller =
                new LiuhaoPaymentCallbackController(service);
        LinkedMultiValueMap<String, String> repeated = parameters();
        repeated.add("pid", "1002");

        assertThatThrownBy(() -> controller.notify(repeated))
                .isInstanceOfSatisfying(
                        LiuhaoPaymentCallbackTransportException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(
                                LiuhaoPaymentCallbackTransportException.Reason.REPEATED_PARAMETER));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMissingRequiredParameterBeforeCallingTheService() {
        assertRejected(
                valuesWithout("sign"),
                LiuhaoPaymentCallbackTransportException.Reason.MISSING_REQUIRED);
    }

    @Test
    void rejectsInvalidParameterNameBeforeCallingTheService() {
        LinkedMultiValueMap<String, String> parameters = parameters();
        parameters.add("invalid-name", "value");

        assertRejected(
                parameters,
                LiuhaoPaymentCallbackTransportException.Reason.INVALID_PARAMETER_NAME);
    }

    @Test
    void rejectsControlCharactersBeforeCallingTheService() {
        LinkedMultiValueMap<String, String> parameters = parameters();
        parameters.set("buyer", "buyer\r\nvalue");

        assertRejected(
                parameters,
                LiuhaoPaymentCallbackTransportException.Reason.INVALID_PARAMETER_VALUE);
    }

    @Test
    void rejectsThirtyThirdParameterBeforeCallingTheService() {
        LinkedMultiValueMap<String, String> parameters = parameters();
        for (int index = 0; index < 17; index++) {
            parameters.add("extension_" + index, "value");
        }

        assertRejected(
                parameters,
                LiuhaoPaymentCallbackTransportException.Reason.TOO_MANY_PARAMETERS);
    }

    @Test
    void rejectsValueLargerThanFourKilobytesBeforeCallingTheService() {
        LinkedMultiValueMap<String, String> parameters = parameters();
        parameters.set("buyer", "x".repeat(4097));

        assertRejected(
                parameters,
                LiuhaoPaymentCallbackTransportException.Reason.VALUE_TOO_LARGE);
    }

    @Test
    void rejectsAggregatePayloadLargerThanSixteenKilobytesBeforeCallingTheService() {
        LinkedMultiValueMap<String, String> parameters = parameters();
        for (int index = 0; index < 4; index++) {
            parameters.add("large_extension_" + index, "x".repeat(4096));
        }

        assertRejected(
                parameters,
                LiuhaoPaymentCallbackTransportException.Reason.PAYLOAD_TOO_LARGE);
    }

    private static LinkedMultiValueMap<String, String> parameters() {
        LinkedMultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        values.add("pid", "1001");
        values.add("trade_no", "202608301234567890");
        values.add("out_trade_no", "AaAjECcaAQGqi_h2Rl1PiA");
        values.add("api_trade_no", "channel-trade-1");
        values.add("type", "alipay");
        values.add("trade_status", "TRADE_SUCCESS");
        values.add("addtime", "2026-08-30 03:39:00");
        values.add("endtime", "2026-08-30 03:40:00");
        values.add("name", "会员支付订单");
        values.add("money", "0.05");
        values.add("param", "");
        values.add("buyer", "masked-buyer");
        values.add("timestamp", "1788062400");
        values.add("sign", "masked-signature");
        values.add("sign_type", "RSA");
        values.add("future_flag", "future-value");
        return values;
    }

    private static LinkedMultiValueMap<String, String> valuesWithout(String removed) {
        LinkedMultiValueMap<String, String> values = parameters();
        values.remove(removed);
        return values;
    }

    private static Map<String, String> singleValues(
            LinkedMultiValueMap<String, String> parameters) {
        Map<String, String> result = new LinkedHashMap<>();
        parameters.forEach((name, values) -> result.put(name, values.getFirst()));
        return Map.copyOf(result);
    }

    private static void assertRejected(
            LinkedMultiValueMap<String, String> parameters,
            LiuhaoPaymentCallbackTransportException.Reason reason) {
        LiuhaoPaymentCallbackService service = mock(LiuhaoPaymentCallbackService.class);
        LiuhaoPaymentCallbackController controller =
                new LiuhaoPaymentCallbackController(service);

        assertThatThrownBy(() -> controller.notify(parameters))
                .isInstanceOfSatisfying(
                        LiuhaoPaymentCallbackTransportException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(reason));
        verifyNoInteractions(service);
    }
}
