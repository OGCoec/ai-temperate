package com.example.temperate.service.user.membership.payment.provider.liuhao;

import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCheckoutResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCloseResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentCreateResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentQueryResult;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundCommand;
import com.example.temperate.service.user.membership.payment.provider.PaymentRefundResult;

/** 该客户端接口是来隔离六号易支付 V2 的页面提交、支付宝统一下单、查询、关单和退款 HTTP 协议。 */
public interface LiuhaoPaymentClient {

    PaymentCheckoutResult createCheckout(PaymentCheckoutCommand command);

    PaymentCreateResult createPayment(PaymentCreateCommand command);

    /** 只查询并恢复已经存在的六号微信交易，不重新提交支付订单。 */
    PaymentCreateResult recoverPayment(PaymentCreateCommand command, String providerTradeNo);

    PaymentQueryResult queryPayment(PaymentQueryCommand command);

    PaymentCloseResult closePayment(PaymentCloseCommand command);

    PaymentRefundResult refundPayment(PaymentRefundCommand command);
}
