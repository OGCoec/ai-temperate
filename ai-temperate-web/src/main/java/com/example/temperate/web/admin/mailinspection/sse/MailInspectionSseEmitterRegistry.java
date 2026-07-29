package com.example.temperate.web.admin.mailinspection.sse;

import com.example.temperate.service.admin.mailinspection.event.MailInspectionJobEvent;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 管理本实例邮件任务 SSE 连接、单管理员会话上限和快照期间的通知暂存。
 */
public interface MailInspectionSseEmitterRegistry {

    Registration register(
            String adminSessionKey,
            String jobId,
            String jobHash,
            BiConsumer<Registration, MailInspectionJobEvent> listener);

    List<Registration> registrations();

    /**
     * 表示一条 SSE 连接的并发安全游标和快照缓冲状态。
     */
    interface Registration {

        SseEmitter emitter();

        String jobId();

        String jobHash();

        long lastRevision();

        boolean markResultSent(int lineNumber);

        void resetResultCursor();

        void advance(long revision);

        List<MailInspectionJobEvent> activate(long snapshotRevision);

        boolean closed();

        void close();

        void closeWithError(Throwable failure);
    }
}
