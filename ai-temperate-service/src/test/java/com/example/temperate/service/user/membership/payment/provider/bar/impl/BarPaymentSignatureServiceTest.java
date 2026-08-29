package com.example.temperate.service.user.membership.payment.provider.bar.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 该测试是来固定 BAR 参数排序、空值排除、sign 排除、sign_type 保留、固定签名向量以及篡改拒绝合同。
 */
class BarPaymentSignatureServiceTest {

    private static final String API_KEY =
            "bar_sk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private final BarPaymentSignatureService signatures =
            new BarPaymentSignatureServiceImpl(Map.of(1, API_KEY));

    @Test
    void canonicalizesDocumentExampleWithoutAdditionalUrlEncoding() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "alipay");
        parameters.put("pid", "1001");
        parameters.put("sign", "ignored");
        parameters.put("param", "");
        parameters.put("money", "19.90");
        parameters.put("name", "会员测试订单");
        parameters.put("out_trade_no", "MEMBER-20260821-0001");
        parameters.put("notify_url", "https://niko000o.site/api/payment/bar/notify");
        parameters.put("return_url", "https://niko000o.site/payment/result");
        parameters.put("timestamp", "1787337600");
        parameters.put("key_version", "1");
        parameters.put("sign_type", "HMAC-SHA256");

        assertThat(signatures.canonicalize(parameters)).isEqualTo(
                "key_version=1&money=19.90&name=会员测试订单"
                        + "&notify_url=https://niko000o.site/api/payment/bar/notify"
                        + "&out_trade_no=MEMBER-20260821-0001&pid=1001"
                        + "&return_url=https://niko000o.site/payment/result"
                        + "&sign_type=HMAC-SHA256&timestamp=1787337600&type=alipay");
        assertThat(signatures.sign(parameters, 1).get("sign")).isEqualTo(
                "2bc2ae7dc7b4aa85ae93a4afafc54b369fc7c6424c38c3d6022321c88b5d32b5");
    }

    @Test
    void signsAndRejectsAChangedScalar() {
        Map<String, String> signed = signatures.sign(Map.of(
                "pid", "1001",
                "timestamp", "1787337600",
                "key_version", "1",
                "sign_type", "HMAC-SHA256"), 1);

        assertThat(signed.get("sign")).matches("^[0-9a-f]{64}$");
        assertThat(signatures.verify(signed, 1)).isTrue();
        Map<String, String> changed = new LinkedHashMap<>(signed);
        changed.put("pid", "1002");
        assertThat(signatures.verify(changed, 1)).isFalse();
    }

    @Test
    void rejectsNestedSignedFields() {
        assertThatThrownBy(() -> signatures.sign(
                        Map.of("data", Map.of("nested", true)), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
