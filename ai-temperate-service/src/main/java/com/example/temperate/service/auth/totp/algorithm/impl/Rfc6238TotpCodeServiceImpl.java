package com.example.temperate.service.auth.totp.algorithm.impl;

import com.example.temperate.service.auth.totp.algorithm.TotpCodeService;
import com.example.temperate.service.auth.totp.config.TotpProperties;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 使用 HMAC-SHA1 实现 RFC 6238 六位 TOTP，并生成认证器兼容的 Base32 配置材料。
 *
 * <p>每次计算都创建独立 {@link Mac}，避免单例 Service 共享可变密码学对象；校验固定覆盖前一、当前和后一
 * 时间片，并使用常量时间字节比较降低验证码差异的时序泄露。</p>
 */
@Service
public final class Rfc6238TotpCodeServiceImpl implements TotpCodeService {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final String HMAC_ALGORITHM = "HmacSHA1";

    private final TotpProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public Rfc6238TotpCodeServiceImpl(TotpProperties properties) {
        this(properties, new SecureRandom());
    }

    Rfc6238TotpCodeServiceImpl(
            TotpProperties properties,
            SecureRandom secureRandom) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.secureRandom = Objects.requireNonNull(
                secureRandom, "secureRandom must not be null");
    }

    @Override
    public byte[] newSecret() {
        byte[] secret = new byte[properties.secretBytes()];
        secureRandom.nextBytes(secret);
        return secret;
    }

    @Override
    public String encodeBase32(byte[] secret) {
        byte[] input = requireSecret(secret);
        try {
            StringBuilder encoded = new StringBuilder((input.length * 8 + 4) / 5);
            int buffer = 0;
            int bits = 0;
            for (byte value : input) {
                buffer = (buffer << 8) | Byte.toUnsignedInt(value);
                bits += 8;
                while (bits >= 5) {
                    encoded.append(BASE32[(buffer >>> (bits - 5)) & 31]);
                    bits -= 5;
                }
            }
            if (bits > 0) {
                encoded.append(BASE32[(buffer << (5 - bits)) & 31]);
            }
            return encoded.toString();
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    @Override
    public OptionalLong findMatchingTimeStep(
            byte[] secret,
            String code,
            Instant now) {
        byte[] validSecret = requireSecret(secret);
        try {
            if (code == null || !code.matches("^[0-9]{6}$") || now == null) {
                return OptionalLong.empty();
            }
            long currentStep = Math.floorDiv(
                    now.getEpochSecond(), properties.period().toSeconds());
            byte[] provided = code.getBytes(StandardCharsets.US_ASCII);
            OptionalLong matched = OptionalLong.empty();
            // 三个候选时间片全部计算后再返回，避免命中位置改变密码学计算次数。
            for (int drift = -properties.allowedDriftSteps();
                    drift <= properties.allowedDriftSteps();
                    drift++) {
                long candidateStep = currentStep + drift;
                byte[] expected = codeFor(validSecret, candidateStep)
                        .getBytes(StandardCharsets.US_ASCII);
                if (MessageDigest.isEqual(provided, expected) && matched.isEmpty()) {
                    matched = OptionalLong.of(candidateStep);
                }
            }
            return matched;
        } finally {
            Arrays.fill(validSecret, (byte) 0);
        }
    }

    @Override
    public String provisioningUri(String accountLabel, byte[] secret) {
        if (accountLabel == null
                || accountLabel.isBlank()
                || accountLabel.length() > 254
                || !accountLabel.equals(accountLabel.trim())) {
            throw new IllegalArgumentException("TOTP account label is invalid.");
        }
        String issuer = encode(properties.issuer());
        String account = encode(accountLabel);
        return "otpauth://totp/" + issuer + ":" + account
                + "?secret=" + encodeBase32(secret)
                + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=" + properties.digits()
                + "&period=" + properties.period().toSeconds();
    }

    String codeFor(byte[] secret, long timeStep) {
        byte[] validSecret = requireSecret(secret);
        try {
            if (timeStep < 0) {
                throw new IllegalArgumentException("TOTP time step must not be negative.");
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(validSecret, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES)
                    .putLong(timeStep)
                    .array());
            int offset = digest[digest.length - 1] & 0x0F;
            int binary = ((digest[offset] & 0x7F) << 24)
                    | ((digest[offset + 1] & 0xFF) << 16)
                    | ((digest[offset + 2] & 0xFF) << 8)
                    | (digest[offset + 3] & 0xFF);
            int modulus = 1;
            for (int index = 0; index < properties.digits(); index++) {
                modulus *= 10;
            }
            return String.format("%0" + properties.digits() + "d", binary % modulus);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable.", exception);
        } finally {
            Arrays.fill(validSecret, (byte) 0);
        }
    }

    private static byte[] requireSecret(byte[] secret) {
        if (secret == null || secret.length < 20 || secret.length > 64) {
            throw new IllegalArgumentException("TOTP secret length is invalid.");
        }
        return secret.clone();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
