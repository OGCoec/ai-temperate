package com.example.temperate.service.admin.mailinspection.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 验证 Redis Pub/Sub 邮件任务通知只接受固定版本、正 revision 和规范 HMAC 身份。
 */
final class MailInspectionJobEventTest {

    @Test
    void rejectsUnprotectedOrMalformedJobIdentity() {
        assertThatThrownBy(() -> new MailInspectionJobEvent(
                MailInspectionJobEvent.SCHEMA_VERSION,
                "raw-job-id",
                1L,
                MailInspectionJobEventType.STATUS,
                MailInspectionType.OPENAI_STATUS,
                Instant.parse("2026-07-29T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
