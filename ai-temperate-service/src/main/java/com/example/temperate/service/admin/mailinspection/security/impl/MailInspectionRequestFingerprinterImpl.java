package com.example.temperate.service.admin.mailinspection.security.impl;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.AdminMailInspectionCreateCommand;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionRequestFingerprint;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.security.MailInspectionRequestFingerprinter;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 使用从Rabbit载荷根密钥域分离得到的HMAC-SHA256密钥计算长度前缀请求指纹。
 *
 * <p>长度前缀避免分隔符碰撞；派生标签避免把AES根密钥直接复用于请求比较。</p>
 */
@Component
public final class MailInspectionRequestFingerprinterImpl
        implements MailInspectionRequestFingerprinter {

    private static final byte[] DERIVATION_LABEL =
            "ait/admin-mail-inspection/idempotency/v1"
                    .getBytes(StandardCharsets.UTF_8);
    private final byte[] hmacKey;

    public MailInspectionRequestFingerprinterImpl(
            AdminMailInspectionProperties properties) {
        Objects.requireNonNull(properties);
        byte[] root = Base64.getDecoder().decode(
                properties.rabbit().payloadKeyBase64());
        this.hmacKey = hmac(root, DERIVATION_LABEL);
    }

    @Override
    public MailInspectionRequestFingerprint fingerprint(
            MailInspectionType type,
            AdminMailInspectionCreateCommand command) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(command);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, "v1");
                write(output, type.name());
                output.writeInt(command.businessConcurrency());
                output.writeInt(command.credentialLines().size());
                for (String line : command.credentialLines()) {
                    write(output, line);
                }
            }
            return new MailInspectionRequestFingerprint(
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(hmac(hmacKey, bytes.toByteArray())));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "mail inspection request fingerprint encoding failed",
                    exception);
        }
    }

    private static void write(DataOutputStream output, String value)
            throws IOException {
        byte[] encoded = Objects.requireNonNull(value)
                .getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "mail inspection request fingerprint unavailable",
                    exception);
        }
    }
}
