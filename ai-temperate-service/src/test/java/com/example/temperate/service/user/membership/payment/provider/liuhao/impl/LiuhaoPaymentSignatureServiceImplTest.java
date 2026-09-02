package com.example.temperate.service.user.membership.payment.provider.liuhao.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationReason;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 该测试是来固定六号 V2 ASCII 排序、空值和数组排除、SHA256WithRSA 生成及平台公钥验签规则。 */
class LiuhaoPaymentSignatureServiceImplTest {

    @Test
    void signsMerchantRequestsAndVerifiesPlatformResponses() throws Exception {
        KeyPair merchant = keyPair();
        KeyPair platform = keyPair();
        LiuhaoPaymentSignatureServiceImpl service = new LiuhaoPaymentSignatureServiceImpl(
                encoded(merchant.getPrivate().getEncoded()),
                encoded(platform.getPublic().getEncoded()),
                encoded(merchant.getPublic().getEncoded()));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "alipay");
        request.put("empty", "");
        request.put("pid", "1001");
        request.put("ignored", new String[] {"x"});

        Map<String, String> signed = service.sign(request);

        assertThat(service.canonicalize(request)).isEqualTo("pid=1001&type=alipay");
        assertThat(signed).doesNotContainKeys("empty", "ignored");
        assertThat(signed.get("sign_type")).isEqualTo("RSA");
        assertThat(service.verifyMerchantRequest(signed)).isTrue();
        Map<String, String> tamperedRequest = new LinkedHashMap<>(signed);
        tamperedRequest.put("pid", "1002");
        assertThat(service.verifyMerchantRequest(tamperedRequest)).isFalse();
        Signature merchantVerifier = Signature.getInstance("SHA256WithRSA");
        merchantVerifier.initVerify(merchant.getPublic());
        merchantVerifier.update("pid=1001&type=alipay".getBytes(StandardCharsets.UTF_8));
        assertThat(merchantVerifier.verify(Base64.getDecoder().decode(signed.get("sign"))))
                .isTrue();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("timestamp", "1788062400");
        response.put("sign_type", "RSA");
        Signature platformSigner = Signature.getInstance("SHA256WithRSA");
        platformSigner.initSign(platform.getPrivate());
        platformSigner.update(service.canonicalize(response).getBytes(StandardCharsets.UTF_8));
        response.put("sign", Base64.getEncoder().encodeToString(platformSigner.sign()));
        assertThat(service.verify(response)).isTrue();
    }

    @Test
    void reportsMissingSignTypeBeforeCryptographicVerification() throws Exception {
        LiuhaoPaymentSignatureServiceImpl service = service();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("timestamp", "1788152400");
        response.put("sign", "AA==");

        assertThat(service.verifyDetailed(response).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_TYPE_MISSING);
    }

    @Test
    void reportsUnexpectedSignTypeWithoutRetainingRawValue() throws Exception {
        LiuhaoPaymentSignatureServiceImpl service = service();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("timestamp", "1788152400");
        response.put("sign_type", "unexpected-sensitive-value");
        response.put("sign", "AA==");

        assertThat(service.verifyDetailed(response).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_TYPE_UNEXPECTED);
    }

    @Test
    void reportsMissingAndMalformedSignaturesSeparately() throws Exception {
        LiuhaoPaymentSignatureServiceImpl service = service();
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("code", 0);
        missing.put("timestamp", "1788152400");
        missing.put("sign_type", "RSA");
        Map<String, Object> malformed = new LinkedHashMap<>(missing);
        malformed.put("sign", "not-base64%%%");

        assertThat(service.verifyDetailed(missing).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_MISSING);
        assertThat(service.verifyDetailed(malformed).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.SIGN_BASE64_INVALID);
    }

    @Test
    void reportsUnexpectedCanonicalFieldsBeforeRsaVerification() throws Exception {
        LiuhaoPaymentSignatureServiceImpl service = service();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("timestamp", "1788152400");
        response.put("sign_type", "RSA");
        response.put("sign", "AA==");
        response.put("unexpected", Map.of("nested", "value"));

        assertThat(service.verifyDetailed(response).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.CANONICAL_FIELDS_UNEXPECTED);
    }

    @Test
    void reportsPlatformSignatureMismatchAfterCanonicalization() throws Exception {
        LiuhaoPaymentSignatureServiceImpl service = service();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("code", 0);
        response.put("msg", "success");
        response.put("timestamp", "1788152400");
        response.put("sign_type", "RSA");
        response.put("sign", Base64.getEncoder().encodeToString(
                "wrong".getBytes(StandardCharsets.UTF_8)));

        assertThat(service.verifyDetailed(response).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.PLATFORM_SIGNATURE_MISMATCH);
    }

    @Test
    void verifiesEveryOfficialAndFutureCallbackFieldAndRejectsTampering() throws Exception {
        KeyPair merchant = keyPair();
        KeyPair platform = keyPair();
        LiuhaoPaymentSignatureServiceImpl service = new LiuhaoPaymentSignatureServiceImpl(
                encoded(merchant.getPrivate().getEncoded()),
                encoded(platform.getPublic().getEncoded()),
                encoded(merchant.getPublic().getEncoded()));
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("trade_status", "TRADE_SUCCESS");
        callback.put("buyer", "masked-buyer");
        callback.put("pid", "1001");
        callback.put("api_trade_no", "channel-trade-1");
        callback.put("trade_no", "202608301234567890");
        callback.put("out_trade_no", "AaAjECcaAQGqi_h2Rl1PiA");
        callback.put("type", "alipay");
        callback.put("addtime", "2026-08-30 03:39:00");
        callback.put("endtime", "2026-08-30 03:40:00");
        callback.put("name", "会员支付订单");
        callback.put("money", "0.05");
        callback.put("param", "");
        callback.put("timestamp", "1788062400");
        callback.put("future_flag", "future-value");
        callback.put("sign_type", "RSA");
        Signature platformSigner = Signature.getInstance("SHA256WithRSA");
        platformSigner.initSign(platform.getPrivate());
        platformSigner.update(service.canonicalize(callback).getBytes(StandardCharsets.UTF_8));
        callback.put("sign", Base64.getEncoder().encodeToString(platformSigner.sign()));

        assertThat(service.canonicalize(callback)).isEqualTo(
                "addtime=2026-08-30 03:39:00"
                        + "&api_trade_no=channel-trade-1"
                        + "&buyer=masked-buyer"
                        + "&endtime=2026-08-30 03:40:00"
                        + "&future_flag=future-value"
                        + "&money=0.05"
                        + "&name=会员支付订单"
                        + "&out_trade_no=AaAjECcaAQGqi_h2Rl1PiA"
                        + "&pid=1001"
                        + "&timestamp=1788062400"
                        + "&trade_no=202608301234567890"
                        + "&trade_status=TRADE_SUCCESS"
                        + "&type=alipay");
        assertThat(service.verifyDetailed(callback).verified()).isTrue();

        Map<String, Object> tampered = new LinkedHashMap<>(callback);
        tampered.put("future_flag", "tampered-value");

        assertThat(service.verifyDetailed(tampered).reason())
                .isEqualTo(LiuhaoSignatureVerificationReason.PLATFORM_SIGNATURE_MISMATCH);
    }

    private static LiuhaoPaymentSignatureServiceImpl service() throws Exception {
        KeyPair merchant = keyPair();
        KeyPair platform = keyPair();
        return new LiuhaoPaymentSignatureServiceImpl(
                encoded(merchant.getPrivate().getEncoded()),
                encoded(platform.getPublic().getEncoded()),
                encoded(merchant.getPublic().getEncoded()));
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }
}
