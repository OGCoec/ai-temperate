package com.example.temperate.service.audit.access.store;

import com.example.temperate.model.audit.access.AccessRequestAuditRecord;
import java.util.List;

/**
 * 定义访问审计记录的一次有界、限时批量事务写入边界，消费者只在该调用提交成功后确认消息。
 */
public interface AccessRequestAuditStoreService {

    void storeBatch(List<AccessRequestAuditRecord> records);
}
