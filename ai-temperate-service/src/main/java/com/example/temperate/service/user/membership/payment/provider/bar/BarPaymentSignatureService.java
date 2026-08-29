package com.example.temperate.service.user.membership.payment.provider.bar;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.util.Map;

/**
 * 该服务是来按 BAR 合同规范化标量参数、生成请求签名并常量时间验证响应或回调签名。
 */
public interface BarPaymentSignatureService {

    Map<String, String> sign(Map<String, ?> parameters, int keyVersion);

    boolean verify(Map<String, ?> parameters, int keyVersion);

    String canonicalize(Map<String, ?> parameters);

    HmacIdentifier identify(int keyVersion, String purpose, String canonicalValue);

    String payloadDigest(Map<String, ?> parameters);
}
