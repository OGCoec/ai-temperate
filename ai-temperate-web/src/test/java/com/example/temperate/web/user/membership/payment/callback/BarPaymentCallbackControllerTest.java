package com.example.temperate.web.user.membership.payment.callback;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackCommand;
import com.example.temperate.service.user.membership.payment.callback.BarPaymentCallbackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 该测试是来固定 BAR 回调只公开 GET、拒绝重复参数并返回严格小写 UTF-8 纯文本 success。
 */
class BarPaymentCallbackControllerTest {

    private BarPaymentCallbackService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(BarPaymentCallbackService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new BarPaymentCallbackController(service))
                .setControllerAdvice(new BarPaymentCallbackExceptionHandler())
                .build();
    }

    @Test
    void acceptsOnlyWhitelistedSingleValueGetParameters() throws Exception {
        mockMvc.perform(validGet())
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("success"));

        ArgumentCaptor<BarPaymentCallbackCommand> captor =
                ArgumentCaptor.forClass(BarPaymentCallbackCommand.class);
        verify(service).receive(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().outTradeNo())
                .isEqualTo("AaAjECcaAQGqi_h2Rl1PiA");
    }

    @Test
    void doesNotExposePostAndRejectsRepeatedQueryFields() throws Exception {
        mockMvc.perform(post(BarPaymentCallbackController.CALLBACK_PATH))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(validGet().param("pid", "1001"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("fail"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            validGet() {
        return get(BarPaymentCallbackController.CALLBACK_PATH)
                .param("pid", "1001")
                .param("trade_no", "1234567890123456789")
                .param("out_trade_no", "AaAjECcaAQGqi_h2Rl1PiA")
                .param("api_trade_no", "BAR-P-1234567890123456790")
                .param("type", "alipay")
                .param("name", "会员模拟支付订单")
                .param("money", "20.00")
                .param("trade_status", "TRADE_SUCCESS")
                .param("timestamp", "1787337720")
                .param("key_version", "1")
                .param("sign_type", "HMAC-SHA256")
                .param("sign", "0".repeat(64));
    }
}
