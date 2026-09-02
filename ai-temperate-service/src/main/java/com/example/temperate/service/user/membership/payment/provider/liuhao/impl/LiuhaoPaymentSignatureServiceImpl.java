package com.example.temperate.service.user.membership.payment.provider.liuhao.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoPaymentSignatureService;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationReason;
import com.example.temperate.service.user.membership.payment.provider.liuhao.LiuhaoSignatureVerificationResult;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来解析六号 V2 的 PKCS#8/X.509 RSA 密钥并执行稳定的 ASCII 参数排序签名。
 *
 * <p>私钥只存在于该 Bean 的内存对象中；签名服务不会输出密钥、完整签名或原始请求。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.liuhao",
        name = "enabled",
        havingValue = "true")
public final class LiuhaoPaymentSignatureServiceImpl implements LiuhaoPaymentSignatureService {

    private static final Base64.Encoder BASE64_URL =
            Base64.getUrlEncoder().withoutPadding();

    private final PrivateKey merchantPrivateKey;
    private final PublicKey merchantPublicKey;
    private final PublicKey platformPublicKey;
    private final HmacSha256Identifier internalIdentifier;

    @Autowired
    public LiuhaoPaymentSignatureServiceImpl(MembershipPaymentProperties properties) {
        MembershipPaymentProperties.Liuhao config =
                Objects.requireNonNull(properties).liuhao();
        this.merchantPrivateKey = parsePrivateKey(config.merchantPrivateKeyB64());
        this.platformPublicKey = parsePublicKey(config.platformPublicKeyB64());
        this.merchantPublicKey = parsePublicKey(config.merchantPublicKeyB64());
        this.internalIdentifier = new HmacSha256Identifier(sha256(merchantPrivateKey.getEncoded()));
        verifyMerchantKeyPair(merchantPublicKey);
    }

    /** 该构造器只供同包密码学测试注入临时密钥，生产装配始终从类型安全 Secret 配置读取。 */
    LiuhaoPaymentSignatureServiceImpl(
            String merchantPrivateKeyB64,
            String platformPublicKeyB64,
            String merchantPublicKeyB64) {
        this.merchantPrivateKey = parsePrivateKey(merchantPrivateKeyB64);
        this.platformPublicKey = parsePublicKey(platformPublicKeyB64);
        this.merchantPublicKey = parsePublicKey(merchantPublicKeyB64);
        this.internalIdentifier = new HmacSha256Identifier(sha256(merchantPrivateKey.getEncoded()));
        verifyMerchantKeyPair(merchantPublicKey);
    }

    @Override
    public Map<String, String> sign(Map<String, ?> parameters) {
        Map<String, String> values = scalarValues(parameters);
        values.remove("sign");
        values.remove("sign_type");
        String signature = signBytes(canonicalize(values).getBytes(StandardCharsets.UTF_8));
        values.put("sign_type", "RSA");
        values.put("sign", signature);
        return Map.copyOf(values);
    }

    @Override
    public boolean verifyMerchantRequest(Map<String, ?> parameters) {
        Objects.requireNonNull(parameters);
        Object rawSignType = parameters.get("sign_type");
        Object rawSignature = parameters.get("sign");
        if (!"RSA".equals(rawSignType)
                || !(rawSignature instanceof String encoded)
                || encoded.isBlank()
                || containsNonScalarValue(parameters)) {
            return false;
        }
        try {
            Map<String, String> values = scalarValues(parameters);
            values.remove("sign");
            values.remove("sign_type");
            Signature verifier = Signature.getInstance("SHA256WithRSA");
            verifier.initVerify(merchantPublicKey);
            verifier.update(canonicalize(values).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(encoded));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public LiuhaoSignatureVerificationResult verifyDetailed(Map<String, ?> parameters) {
        Objects.requireNonNull(parameters);
        Object rawSignType = parameters.get("sign_type");
        if (rawSignType == null
                || (rawSignType instanceof String text && text.isBlank())) {
            return failed(LiuhaoSignatureVerificationReason.SIGN_TYPE_MISSING);
        }
        if (!(rawSignType instanceof String signType) || !"RSA".equals(signType)) {
            return failed(LiuhaoSignatureVerificationReason.SIGN_TYPE_UNEXPECTED);
        }

        Object rawSignature = parameters.get("sign");
        if (!(rawSignature instanceof String encoded) || encoded.isBlank()) {
            return failed(LiuhaoSignatureVerificationReason.SIGN_MISSING);
        }

        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            return failed(LiuhaoSignatureVerificationReason.SIGN_BASE64_INVALID);
        }

        byte[] canonical;
        try {
            // 响应中的复合字段不得像请求附件一样被静默排除，否则验签集合会与实际 JSON 语义分离。
            if (containsNonScalarValue(parameters)) {
                return failed(LiuhaoSignatureVerificationReason.CANONICAL_FIELDS_UNEXPECTED);
            }
            Map<String, String> values = scalarValues(parameters);
            values.remove("sign");
            values.remove("sign_type");
            canonical = canonicalize(values).getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return failed(LiuhaoSignatureVerificationReason.CANONICAL_FIELDS_UNEXPECTED);
        }

        try {
            Signature verifier = Signature.getInstance("SHA256WithRSA");
            verifier.initVerify(platformPublicKey);
            verifier.update(canonical);
            try {
                return verifier.verify(signature)
                        ? LiuhaoSignatureVerificationResult.success()
                        : failed(LiuhaoSignatureVerificationReason.PLATFORM_SIGNATURE_MISMATCH);
            } catch (java.security.SignatureException exception) {
                // 能完成 Base64 解码但不满足 RSA 签名长度或内容，同样属于平台签名不匹配而非运行环境不可用。
                return failed(LiuhaoSignatureVerificationReason.PLATFORM_SIGNATURE_MISMATCH);
            }
        } catch (java.security.GeneralSecurityException exception) {
            return failed(LiuhaoSignatureVerificationReason.CRYPTO_VERIFIER_UNAVAILABLE);
        }
    }

    @Override
    public String canonicalize(Map<String, ?> parameters) {
        return scalarValues(parameters).entrySet().stream()
                .filter(entry -> !"sign".equals(entry.getKey())
                        && !"sign_type".equals(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> right,
                        TreeMap::new))
                .entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    @Override
    public HmacIdentifier identify(String purpose, String canonicalValue) {
        return internalIdentifier.identify(
                Objects.requireNonNull(purpose) + "\n" + Objects.requireNonNull(canonicalValue));
    }

    @Override
    public String payloadDigest(Map<String, ?> parameters) {
        return BASE64_URL.encodeToString(sha256(
                canonicalize(parameters).getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, String> scalarValues(Map<String, ?> parameters) {
        TreeMap<String, String> values = new TreeMap<>();
        Objects.requireNonNull(parameters).forEach((name, value) -> {
            if (name == null || !name.matches("^[A-Za-z0-9_]+$")) {
                throw new IllegalArgumentException("Liuhao parameter name is invalid.");
            }
            if (value == null) {
                return;
            }
            // 六号签名合同明确排除数组和文件；映射、集合与二进制字段同样不进入待签名标量集。
            if (value.getClass().isArray()
                    || value instanceof Iterable<?>
                    || value instanceof Map<?, ?>
                    || value instanceof org.springframework.core.io.Resource) {
                return;
            }
            if (value instanceof String text) {
                if (!text.isEmpty()) {
                    values.put(name, text);
                }
                return;
            }
            if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
                values.put(name, String.valueOf(value));
                return;
            }
            throw new IllegalArgumentException("Liuhao signed fields must be scalar values.");
        });
        return new LinkedHashMap<>(values);
    }

    private static boolean containsNonScalarValue(Map<String, ?> parameters) {
        return parameters.values().stream().anyMatch(value -> value != null
                && (value.getClass().isArray()
                        || value instanceof Iterable<?>
                        || value instanceof Map<?, ?>
                        || value instanceof org.springframework.core.io.Resource));
    }

    private static LiuhaoSignatureVerificationResult failed(
            LiuhaoSignatureVerificationReason reason) {
        return LiuhaoSignatureVerificationResult.failed(reason);
    }

    private String signBytes(byte[] payload) {
        try {
            Signature signer = Signature.getInstance("SHA256WithRSA");
            signer.initSign(merchantPrivateKey);
            signer.update(payload);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Liuhao RSA signing is unavailable.", exception);
        }
    }

    private void verifyMerchantKeyPair(PublicKey merchantPublicKey) {
        byte[] challenge = "liuhao-merchant-key-pair-check".getBytes(StandardCharsets.US_ASCII);
        try {
            Signature signer = Signature.getInstance("SHA256WithRSA");
            signer.initSign(merchantPrivateKey);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256WithRSA");
            verifier.initVerify(merchantPublicKey);
            verifier.update(challenge);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("Liuhao merchant key pair does not match.");
            }
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalArgumentException("Liuhao merchant key pair is invalid.", exception);
        }
    }

    private static PrivateKey parsePrivateKey(String encoded) {
        try {
            byte[] der = decodeKey(encoded);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Liuhao merchant private key is invalid.", exception);
        }
    }

    private static PublicKey parsePublicKey(String encoded) {
        try {
            byte[] der = decodeKey(encoded);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Liuhao public key is invalid.", exception);
        }
    }

    private static byte[] decodeKey(String encoded) {
        String value = Objects.requireNonNull(encoded).trim();
        byte[] outer = Base64.getDecoder().decode(value);
        String decoded = new String(outer, StandardCharsets.US_ASCII).trim();
        String pem = decoded.contains("BEGIN") ? decoded : value;
        pem = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(pem);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
