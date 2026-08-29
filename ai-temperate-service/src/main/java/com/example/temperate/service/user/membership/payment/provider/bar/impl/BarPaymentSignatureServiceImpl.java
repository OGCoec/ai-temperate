package com.example.temperate.service.user.membership.payment.provider.bar.impl;

import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.user.membership.payment.config.MembershipPaymentProperties;
import com.example.temperate.service.user.membership.payment.provider.bar.BarPaymentSignatureService;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 该实现是来执行 BAR 的 SHA-256 派生密钥加 HMAC-SHA256 合同，并避免普通字符串比较泄露签名前缀。
 *
 * <p>API Key 只保存在不可变内存映射中，不进入签名结果、异常文本或日志。</p>
 */
@Service
@ConditionalOnProperty(
        prefix = "app.membership-payment.bar",
        name = "enabled",
        havingValue = "true")
public final class BarPaymentSignatureServiceImpl
        implements BarPaymentSignatureService {

    private static final Base64.Encoder BASE64_URL =
            Base64.getUrlEncoder().withoutPadding();
    private static final HexFormat LOWER_HEX = HexFormat.of();

    private final Map<Integer, String> apiKeys;

    @Autowired
    public BarPaymentSignatureServiceImpl(MembershipPaymentProperties properties) {
        this(Objects.requireNonNull(properties).bar().apiKeys());
    }

    /** 该构造器只供同包单元测试注入占位密钥，生产装配始终来自类型安全配置。 */
    BarPaymentSignatureServiceImpl(Map<Integer, String> apiKeys) {
        this.apiKeys = Map.copyOf(Objects.requireNonNull(apiKeys));
    }

    @Override
    public Map<String, String> sign(Map<String, ?> parameters, int keyVersion) {
        Map<String, String> signed = new LinkedHashMap<>();
        Objects.requireNonNull(parameters).forEach((name, value) -> {
            String scalar = scalar(value);
            if (!"sign".equals(name) && scalar != null && !scalar.isBlank()) {
                signed.put(name, scalar);
            }
        });
        signed.put("sign", calculate(signed, requireApiKey(keyVersion)));
        return Map.copyOf(signed);
    }

    @Override
    public boolean verify(Map<String, ?> parameters, int keyVersion) {
        Map<String, ?> value = Objects.requireNonNull(parameters);
        Object suppliedValue = value.get("sign");
        if (!(suppliedValue instanceof String supplied)
                || !supplied.matches("^[0-9a-f]{64}$")) {
            return false;
        }
        String expected = calculate(value, requireApiKey(keyVersion));
        return MessageDigest.isEqual(
                LOWER_HEX.parseHex(expected),
                LOWER_HEX.parseHex(supplied));
    }

    /**
     * 只接受 BAR 合同允许的简单标量；嵌套对象或数组会在签名前失败，避免双方对 JSON 序列化产生歧义。
     */
    @Override
    public String canonicalize(Map<String, ?> parameters) {
        TreeMap<String, String> sorted = new TreeMap<>();
        Objects.requireNonNull(parameters).forEach((name, value) -> {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("BAR parameter name is invalid.");
            }
            String scalar = scalar(value);
            if (!"sign".equals(name) && scalar != null && !scalar.isBlank()) {
                sorted.put(name, scalar);
            }
        });
        return sorted.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    @Override
    public HmacIdentifier identify(
            int keyVersion,
            String purpose,
            String canonicalValue) {
        String context = Objects.requireNonNull(purpose) + "\n"
                + Objects.requireNonNull(canonicalValue);
        return new HmacSha256Identifier(derivedKey(requireApiKey(keyVersion)))
                .identify(context);
    }

    @Override
    public String payloadDigest(Map<String, ?> parameters) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return BASE64_URL.encodeToString(digest.digest(
                    canonicalize(parameters).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String calculate(Map<String, ?> parameters, String rawApiKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(derivedKey(rawApiKey), "HmacSHA256"));
            return LOWER_HEX.formatHex(mac.doFinal(
                    canonicalize(parameters).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", exception);
        }
    }

    private String requireApiKey(int keyVersion) {
        String apiKey = apiKeys.get(keyVersion);
        if (apiKey == null) {
            throw new IllegalArgumentException("BAR key version is unavailable.");
        }
        return apiKey;
    }

    private static byte[] derivedKey(String rawApiKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    rawApiKey.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String scalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        throw new IllegalArgumentException("BAR signed fields must be scalar values.");
    }
}
