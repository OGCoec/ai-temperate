package com.example.temperate.service.admin.mailinspection.strategy;

import com.example.temperate.service.admin.mailinspection.domain.MailInspectionResult;
import com.example.temperate.service.admin.mailinspection.domain.MailInspectionType;
import com.example.temperate.service.admin.mailinspection.domain.MailboxCredential;
import reactor.core.publisher.Mono;

/**
 * 定义单行邮箱凭证的统一检查策略，具体实现只负责一种稳定业务类型。
 */
public interface MailInspectionStrategy {

    MailInspectionType type();

    Mono<MailInspectionResult> inspect(MailboxCredential credential);
}
