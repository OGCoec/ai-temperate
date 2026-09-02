package com.example.temperate.service.user.membership.payment.provider.liuhao;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import java.util.Map;

/** 该接口是来按六号易支付 V2 规则生成和验证 SHA256WithRSA 签名，隔离密钥解析与业务 Provider。 */
public interface LiuhaoPaymentSignatureService {

    Map<String, String> sign(Map<String, ?> parameters);

    /** 使用本地商户公钥复验即将发送的请求签名，失败时调用方必须禁止网络发送。 */
    boolean verifyMerchantRequest(Map<String, ?> parameters);

    /** 按固定检查顺序返回详细验签原因，结果不得包含签名原文、规范串或密钥。 */
    LiuhaoSignatureVerificationResult verifyDetailed(Map<String, ?> parameters);

    /** 为既有回调调用保留布尔入口，实际裁决统一委托给详细验签结果。 */
    default boolean verify(Map<String, ?> parameters) {
        return verifyDetailed(parameters).verified();
    }

    String canonicalize(Map<String, ?> parameters);

    HmacIdentifier identify(String purpose, String canonicalValue);

    String payloadDigest(Map<String, ?> parameters);
}
