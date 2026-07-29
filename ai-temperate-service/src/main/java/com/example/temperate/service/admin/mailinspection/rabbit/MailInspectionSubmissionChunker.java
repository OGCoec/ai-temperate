package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 按Submission二进制明文的真实UTF-8尺寸切分凭证，确保加密前每块不超过配置边界。
 */
@Component
public final class MailInspectionSubmissionChunker {

    private static final int CHUNK_HEADER_BYTES = Integer.BYTES;
    private static final int ENTRY_FIXED_BYTES = Integer.BYTES * 4;
    private final int maxPlaintextBytes;

    public MailInspectionSubmissionChunker(
            AdminMailInspectionProperties properties) {
        this.maxPlaintextBytes = Objects.requireNonNull(properties)
                .submission()
                .maxPlaintextChunkBytes();
    }

    public List<List<MailboxCredential>> chunk(
            List<MailboxCredential> credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        if (credentials.isEmpty()) {
            return List.of();
        }
        List<List<MailboxCredential>> chunks = new ArrayList<>();
        List<MailboxCredential> current = new ArrayList<>();
        int currentBytes = CHUNK_HEADER_BYTES;
        for (MailboxCredential credential : credentials) {
            int entryBytes = encodedBytes(credential);
            if (entryBytes + CHUNK_HEADER_BYTES > maxPlaintextBytes) {
                throw new MailInspectionPayloadException(
                        "mail inspection submission entry exceeds chunk boundary");
            }
            if (!current.isEmpty()
                    && currentBytes + entryBytes > maxPlaintextBytes) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentBytes = CHUNK_HEADER_BYTES;
            }
            current.add(credential);
            currentBytes += entryBytes;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        return List.copyOf(chunks);
    }

    static int encodedBytes(MailboxCredential credential) {
        return ENTRY_FIXED_BYTES
                + utf8Length(credential.email())
                + utf8Length(credential.clientId())
                + utf8Length(credential.refreshToken());
    }

    private static int utf8Length(String value) {
        return Objects.requireNonNull(value)
                .getBytes(StandardCharsets.UTF_8)
                .length;
    }
}
