package com.example.temperate.service.admin.mailinspection.job.redis;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.common.security.hmac.HmacSha256Identifier;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 使用邮件任务独立密钥和用途隔离 HMAC 生成 Redis 索引，并提供不泄露公开 ID 的短诊断引用。
 */
@Component
public final class MailInspectionJobKeyHasher {

    private static final String JOB_PURPOSE = "mail-job-id:v2";
    private static final String REQUEST_PURPOSE = "mail-client-request-id:v2";
    private static final int JOB_REF_LENGTH = 16;

    private final HmacSha256Identifier hmac;
    private final HybridBase64UrlCodec jobIdCodec;

    public MailInspectionJobKeyHasher(
            AdminMailInspectionProperties properties,
            HybridBase64UrlCodec jobIdCodec) {
        this.jobIdCodec = Objects.requireNonNull(jobIdCodec);
        this.hmac = new HmacSha256Identifier(decodeSecret(
                properties.job().keyHmacSecretBase64()));
    }

    public HmacIdentifier hashJobId(String jobId) {
        // 先执行规范解码，避免多个文本表示被哈希成不同 Redis 身份。
        jobIdCodec.decode(jobId);
        return hmac.identify(
                JOB_PURPOSE,
                jobId.getBytes(StandardCharsets.US_ASCII));
    }

    public HmacIdentifier hashClientRequestId(String clientRequestId) {
        if (clientRequestId == null
                || clientRequestId.isBlank()
                || clientRequestId.length() > 128) {
            throw new IllegalArgumentException(
                    "mail inspection client request ID is invalid");
        }
        return hmac.identify(
                REQUEST_PURPOSE,
                clientRequestId.getBytes(StandardCharsets.UTF_8));
    }

    public String jobRef(String jobId) {
        return hashJobId(jobId).value().substring(0, JOB_REF_LENGTH);
    }

    private static byte[] decodeSecret(String encoded) {
        try {
            byte[] value = Base64.getDecoder().decode(encoded);
            if (value.length < HmacSha256Identifier.MINIMUM_SECRET_BYTES
                    || !Base64.getEncoder().encodeToString(value)
                    .equals(encoded)) {
                throw new IllegalArgumentException(
                        "mail inspection job HMAC secret is invalid");
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "mail inspection job HMAC secret must be canonical Base64",
                    exception);
        }
    }
}
