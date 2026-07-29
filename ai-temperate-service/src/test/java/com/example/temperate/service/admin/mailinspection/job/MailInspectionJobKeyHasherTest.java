package com.example.temperate.service.admin.mailinspection.job;

import com.example.temperate.common.codec.id.HybridBase64UrlCodec;
import com.example.temperate.common.security.hmac.HmacIdentifier;
import com.example.temperate.service.admin.mailinspection.config.AdminMailInspectionProperties;
import com.example.temperate.service.admin.mailinspection.job.redis.MailInspectionJobKeyHasher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证邮件任务与幂等请求使用独立 HMAC 用途域，并在哈希前拒绝非规范 Job ID。
 */
final class MailInspectionJobKeyHasherTest {

    private final HybridBase64UrlCodec codec = new HybridBase64UrlCodec();
    private final MailInspectionJobKeyHasher hasher = new MailInspectionJobKeyHasher(
            AdminMailInspectionProperties.defaults(), codec);

    @Test
    void separatesJobAndClientRequestDomains() {
        String sharedText = codec.encode(
                "0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

        HmacIdentifier job = hasher.hashJobId(sharedText);
        HmacIdentifier request = hasher.hashClientRequestId(sharedText);

        assertNotEquals(job, request);
        assertEquals(43, job.value().length());
        assertEquals(job.value().substring(0, 16), hasher.jobRef(sharedText));
    }

    @Test
    void rejectsNonCanonicalJobIdsBeforeHashing() {
        assertThrows(IllegalArgumentException.class,
                () -> hasher.hashJobId("AAAAAAAAAAA"));
        assertThrows(IllegalArgumentException.class,
                () -> hasher.hashJobId("A".repeat(21) + "="));
    }
}
