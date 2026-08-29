package com.example.temperate.service.user.membership.payment.provider.bar;

import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;

/**
 * 该客户端是来封装 BAR 创建、查询、关闭和模拟退款同步协议，并生成不持久化的浏览器 submit 描述。
 */
public interface BarPaymentClient {

    PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command);

    PaymentQueryResult queryPayment(PaymentQueryCommand command);

    PaymentCloseResult closePayment(PaymentCloseCommand command);

    PaymentRefundResult refundPayment(PaymentRefundCommand command);
}
