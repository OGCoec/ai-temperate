package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 使用AES-256-GCM保护Submission Chunk中的多条邮箱凭证，并通过AAD锁定提交身份和分块位置。
 */
@Component
public final class AdminMailInspectionSubmissionPayloadProtector {

    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_FIELD_BYTES = 16_384;
    private final SecretKey key;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int maxPlaintextBytes;

    public AdminMailInspectionSubmissionPayloadProtector(
            AdminMailInspectionProperties properties) {
        byte[] decoded = Base64.getDecoder().decode(
                properties.rabbit().payloadKeyBase64());
        this.key = new SecretKeySpec(decoded, "AES");
        this.maxPlaintextBytes = properties.submission()
                .maxPlaintextChunkBytes();
    }

    public MailInspectionProtectedPayload protect(
            String messageId,
            String clientRequestId,
            String jobId,
            MailInspectionType type,
            int chunkIndex,
            int chunkCount,
            List<MailboxCredential> credentials) {
        byte[] plaintext = encode(credentials);
        if (plaintext.length > maxPlaintextBytes) {
            throw new MailInspectionPayloadException(
                    "mail inspection submission plaintext exceeds boundary");
        }
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv);
            cipher.updateAAD(aad(
                    messageId,
                    clientRequestId,
                    jobId,
                    type,
                    chunkIndex,
                    chunkCount));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new MailInspectionProtectedPayload(
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(iv),
                    Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new MailInspectionPayloadException(
                    "mail inspection submission encryption failed",
                    exception);
        }
    }

    public List<MailInspectionSubmissionCredential> unprotect(
            MailInspectionSubmissionChunkMessage message) {
        try {
            byte[] iv = Base64.getUrlDecoder().decode(
                    message.protectedPayload().iv());
            if (iv.length != IV_BYTES) {
                throw new MailInspectionPayloadException(
                        "mail inspection submission IV is invalid");
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, iv);
            cipher.updateAAD(aad(
                    message.messageId(),
                    message.clientRequestId(),
                    message.jobId(),
                    message.inspectionType(),
                    message.chunkIndex(),
                    message.chunkCount()));
            byte[] plaintext = cipher.doFinal(
                    Base64.getUrlDecoder().decode(
                            message.protectedPayload().ciphertext()));
            return decode(plaintext);
        } catch (MailInspectionPayloadException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new MailInspectionPayloadException(
                    "mail inspection submission validation failed",
                    exception);
        }
    }

    private Cipher cipher(int mode, byte[] iv)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher;
    }

    private static byte[] aad(
            String messageId,
            String clientRequestId,
            String jobId,
            MailInspectionType type,
            int chunkIndex,
            int chunkCount) {
        return (MailInspectionRabbitNames.SUBMISSION_EVENT_TYPE
                + "\u0000"
                + messageId
                + "\u0000"
                + clientRequestId
                + "\u0000"
                + jobId
                + "\u0000"
                + type.name()
                + "\u0000"
                + chunkIndex
                + "\u0000"
                + chunkCount).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encode(List<MailboxCredential> credentials) {
        int size = Integer.BYTES;
        for (MailboxCredential credential : credentials) {
            size += MailInspectionSubmissionChunker.encodedBytes(credential);
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).putInt(credentials.size());
        for (MailboxCredential credential : credentials) {
            buffer.putInt(credential.lineNumber());
            put(buffer, credential.email());
            put(buffer, credential.clientId());
            put(buffer, credential.refreshToken());
        }
        return buffer.array();
    }

    private static List<MailInspectionSubmissionCredential> decode(
            byte[] plaintext) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(plaintext);
            int count = buffer.getInt();
            if (count < 1) {
                throw new MailInspectionPayloadException(
                        "mail inspection submission count is invalid");
            }
            List<MailInspectionSubmissionCredential> credentials =
                    new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                credentials.add(new MailInspectionSubmissionCredential(
                        buffer.getInt(),
                        read(buffer),
                        read(buffer),
                        read(buffer)));
            }
            if (buffer.hasRemaining()) {
                throw new MailInspectionPayloadException(
                        "mail inspection submission has trailing bytes");
            }
            return List.copyOf(credentials);
        } catch (MailInspectionPayloadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MailInspectionPayloadException(
                    "mail inspection submission structure is invalid",
                    exception);
        }
    }

    private static void put(ByteBuffer buffer, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > MAX_FIELD_BYTES) {
            throw new MailInspectionPayloadException(
                    "mail inspection submission field exceeds boundary");
        }
        buffer.putInt(bytes.length).put(bytes);
    }

    private static String read(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 1 || length > MAX_FIELD_BYTES || length > buffer.remaining()) {
            throw new MailInspectionPayloadException(
                    "mail inspection submission field length is invalid");
        }
        byte[] value = new byte[length];
        buffer.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }
}
