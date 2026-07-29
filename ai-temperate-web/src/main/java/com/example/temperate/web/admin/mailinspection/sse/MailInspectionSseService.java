package com.example.temperate.web.admin.mailinspection.sse;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 建立邮件任务 SSE 权威快照和后续实时事件连接。
 */
public interface MailInspectionSseService {

    SseEmitter connect(
            String jobId,
            String lastEventId,
            String adminSessionKey);
}
