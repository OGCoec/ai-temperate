package com.example.temperate.service.user.membership.payment.callback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 该命令是来无损承载六号易支付 GET 通知的完整标量参数集，并为业务校验提供固定字段访问器。
 *
 * <p>所有字段都保留在不可变 Map 中供 V2 RSA 验签、指纹和摘要复用；未知扩展字段不会直接参与订单裁决。</p>
 */
public record LiuhaoPaymentCallbackCommand(Map<String, String> externalFields) {

    public LiuhaoPaymentCallbackCommand {
        // 防御性复制保证验签、指纹和摘要始终观察同一份字段事实，调用方后续修改不能改变安全裁决。
        Map<String, String> copied = new LinkedHashMap<>();
        Objects.requireNonNull(externalFields, "externalFields must not be null")
                .forEach((name, value) -> copied.put(
                        Objects.requireNonNull(name, "external field name must not be null"),
                        Objects.requireNonNull(value, "external field value must not be null")));
        externalFields = Collections.unmodifiableMap(copied);
    }

    public String pid() {
        return externalFields.get("pid");
    }

    public String tradeNo() {
        return externalFields.get("trade_no");
    }

    public String outTradeNo() {
        return externalFields.get("out_trade_no");
    }

    public String type() {
        return externalFields.get("type");
    }

    public String name() {
        return externalFields.get("name");
    }

    public String money() {
        return externalFields.get("money");
    }

    public String param() {
        return externalFields.get("param");
    }

    public String tradeStatus() {
        return externalFields.get("trade_status");
    }

    public String timestamp() {
        return externalFields.get("timestamp");
    }

    public String signType() {
        return externalFields.get("sign_type");
    }

    public String sign() {
        return externalFields.get("sign");
    }
}
