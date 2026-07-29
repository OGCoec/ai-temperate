package com.example.temperate.service.admin.mailinspection.rabbit;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import reactor.core.publisher.Mono;

/**
 * 将一个已持久化的提交分块可靠转换为逐凭证工作消息，并在派发标记确认后完成原消息确认。
 */
public interface MailInspectionSubmissionDispatcher {

    Mono<Void> dispatch(
            MailInspectionType expectedType,
            MailInspectionSubmissionChunkMessage message);
}
