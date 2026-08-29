package com.example.temperate.service.user.membership.payment.callback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 该命令是来承载 BAR GET 回调的明确白名单标量，并为验签保留与原协议一致的字段名称和值。
 */
public record BarPaymentCallbackCommand(
        String pid,
        String tradeNo,
        String outTradeNo,
        String apiTradeNo,
        String type,
        String name,
        String money,
        String param,
        String tradeStatus,
        String timestamp,
        String keyVersion,
        String signType,
        String sign) {

    public Map<String, String> externalFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pid", pid);
        fields.put("trade_no", tradeNo);
        fields.put("out_trade_no", outTradeNo);
        fields.put("api_trade_no", apiTradeNo);
        fields.put("type", type);
        fields.put("name", name);
        fields.put("money", money);
        if (param != null && !param.isBlank()) {
            fields.put("param", param);
        }
        fields.put("trade_status", tradeStatus);
        fields.put("timestamp", timestamp);
        fields.put("key_version", keyVersion);
        fields.put("sign_type", signType);
        fields.put("sign", sign);
        return Collections.unmodifiableMap(fields);
    }
}
