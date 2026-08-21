package com.example.temperate.service.user.membership.payment.callback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 该命令是来承载 GET、POST 表单或 POST JSON 归一化后的六号模拟支付字段，使三种传输形式共享同一业务校验。
 */
public record SimulatedLiuhaoCallbackCommand(
        String pid,
        String tradeNo,
        String outTradeNo,
        String apiTradeNo,
        String type,
        String tradeStatus,
        String addTime,
        String endTime,
        String name,
        String money,
        String param,
        String buyer,
        String timestamp,
        String sign,
        String signType) {

    /** 返回稳定外部字段名，摘要算法据此排序且明确排除 sign 和 buyer。 */
    public Map<String, String> externalFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("pid", pid);
        fields.put("trade_no", tradeNo);
        fields.put("out_trade_no", outTradeNo);
        fields.put("api_trade_no", apiTradeNo);
        fields.put("type", type);
        fields.put("trade_status", tradeStatus);
        fields.put("addtime", addTime);
        fields.put("endtime", endTime);
        fields.put("name", name);
        fields.put("money", money);
        fields.put("param", param);
        fields.put("buyer", buyer);
        fields.put("timestamp", timestamp);
        fields.put("sign", sign);
        fields.put("sign_type", signType);
        return Map.copyOf(fields);
    }
}
