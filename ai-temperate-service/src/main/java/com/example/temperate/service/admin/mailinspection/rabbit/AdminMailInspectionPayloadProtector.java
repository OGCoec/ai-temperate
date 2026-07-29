package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 使用独立 AES-256-GCM 密钥保护进入 RabbitMQ 的邮箱、clientId 与 refresh token，并把消息身份绑定为 AAD。
 *
 * <p>密码字段在解析阶段后即被丢弃；本组件既不接收密码，也不把明文或密文写入异常和日志。</p>
 */
@Component
public final class AdminMailInspectionPayloadProtector {

    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_FIELD_BYTES = 16_384;

    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();

    public AdminMailInspectionPayloadProtector(
            AdminMailInspectionProperties properties) {
        byte[] keyBytes = Base64.getDecoder().decode(
                properties.rabbit().payloadKeyBase64());
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "mail inspection Rabbit payload key must contain 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public MailInspectionProtectedPayload protect(
            String messageId,
            String jobId,
            String jobKeyHash,
            MailInspectionType type,
            MailboxCredential credential) {
        Objects.requireNonNull(credential, "credential must not be null");
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            cipher.updateAAD(aad(
                    messageId,
                    jobId,
                    jobKeyHash,
                    type,
                    credential.lineNumber()));
            byte[] ciphertext = cipher.doFinal(encode(credential));
            return new MailInspectionProtectedPayload(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(iv),
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new MailInspectionPayloadException(
                    "mail inspection payload encryption failed",
                    exception);
        }
    }

    public MailInspectionProtectedCredential unprotect(
            String messageId,
            String jobId,
            String jobKeyHash,
            MailInspectionType type,
            int lineNumber,
            MailInspectionProtectedPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        try {
            byte[] iv = Base64.getUrlDecoder().decode(payload.iv());
            if (iv.length != IV_BYTES) {
                throw new MailInspectionPayloadException(
                        "mail inspection payload IV is invalid");
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv);
            cipher.updateAAD(aad(
                    messageId, jobId, jobKeyHash, type, lineNumber));
            byte[] plaintext = cipher.doFinal(
                    Base64.getUrlDecoder().decode(payload.ciphertext()));
            return decode(plaintext);
        } catch (MailInspectionPayloadException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new MailInspectionPayloadException(
                    "mail inspection payload validation failed",
                    exception);
        }
    }

    private Cipher cipher(int mode, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    /**
     * AAD 绑定消息、任务、检查类型和原始行号，防止有效密文被复制到另一条工作消息后仍可解密。
     */
    private static byte[] aad(
            String messageId,
            String jobId,
            String jobKeyHash,
            MailInspectionType type,
            int lineNumber) {
        // Schema 与事件类型进入 AAD，使 v2 密文不能被旧消费者或其他 Rabbit 事件类型复用。
        String value = MailInspectionRabbitNames.WORK_SCHEMA_VERSION
                + "\u0000"
                + MailInspectionRabbitNames.EVENT_TYPE
                + "\u0000"
                + Objects.requireNonNull(messageId)
                + "\u0000"
                + Objects.requireNonNull(jobId)
                + "\u0000"
                + Objects.requireNonNull(jobKeyHash)
                + "\u0000"
                + Objects.requireNonNull(type).name()
                + "\u0000"
                + lineNumber;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encode(MailboxCredential credential) {
        byte[] email = bytes(credential.email());
        byte[] clientId = bytes(credential.clientId());
        byte[] refreshToken = bytes(credential.refreshToken());
        return ByteBuffer.allocate(
                        Integer.BYTES * 3
                                + email.length
                                + clientId.length
                                + refreshToken.length)
                .putInt(email.length)
                .put(email)
                .putInt(clientId.length)
                .put(clientId)
                .putInt(refreshToken.length)
                .put(refreshToken)
                .array();
    }

    private static MailInspectionProtectedCredential decode(byte[] plaintext) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(plaintext);
            String email = read(buffer);
            String clientId = read(buffer);
            String refreshToken = read(buffer);
            if (buffer.hasRemaining()) {
                throw new MailInspectionPayloadException(
                        "mail inspection payload has trailing bytes");
            }
            return new MailInspectionProtectedCredential(
                    email,
                    clientId,
                    refreshToken);
        } catch (MailInspectionPayloadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MailInspectionPayloadException(
                    "mail inspection payload structure is invalid",
                    exception);
        }
    }

    private static byte[] bytes(String value) {
        byte[] bytes = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FIELD_BYTES) {
            throw new MailInspectionPayloadException(
                    "mail inspection payload field exceeds boundary");
        }
        return bytes;
    }

    private static String read(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 1
                || length > MAX_FIELD_BYTES
                || length > buffer.remaining()) {
            throw new MailInspectionPayloadException(
                    "mail inspection payload field length is invalid");
        }
        byte[] value = new byte[length];
        buffer.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }
}
