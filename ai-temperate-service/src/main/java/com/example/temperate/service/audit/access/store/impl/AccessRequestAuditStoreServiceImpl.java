package com.example.temperate.service.audit.access.store.impl;

import com.example.temperate.mapper.audit.access.AccessRequestAuditMapper;
import com.example.temperate.model.audit.access.AccessRequestAuditRecord;
import com.example.temperate.service.audit.access.config.AccessAuditProperties;
import com.example.temperate.service.audit.access.store.AccessRequestAuditStoreService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用单条 MyBatis 批量 SQL 幂等写入审计记录，并用短于 RabbitMQ ACK 窗口的事务截止时间限制数据库等待。
 *
 * <p>事务超时会传递给 MyBatis/JDBC Statement，使锁等待或长期 SQL 在 RabbitMQ 关闭消费 channel 前失败并进入有限重试；
 * PostgreSQL socket 级网络等待则由数据源配置单独限制。</p>
 */
@Service
@ConditionalOnProperty(prefix = "app.access-audit", name = "enabled", havingValue = "true")
public final class AccessRequestAuditStoreServiceImpl implements AccessRequestAuditStoreService {

    private final AccessRequestAuditMapper mapper;
    private final int maximumBatchSize;

    @Autowired
    public AccessRequestAuditStoreServiceImpl(
            AccessRequestAuditMapper mapper,
            AccessAuditProperties properties) {
        this(mapper, properties.batchSize());
    }

    AccessRequestAuditStoreServiceImpl(
            AccessRequestAuditMapper mapper,
            int maximumBatchSize) {
        this.mapper = mapper;
        this.maximumBatchSize = maximumBatchSize;
    }

    @Override
    @Transactional(timeoutString = "${app.access-audit.store-timeout-seconds:15}")
    public void storeBatch(List<AccessRequestAuditRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (records.size() > maximumBatchSize) {
            throw new IllegalArgumentException("Access audit batch exceeds the configured boundary.");
        }
        // 复制批次可防止调用方在事务执行期间修改集合，消息 ID 唯一约束保证超时后的重新投递仍然幂等。
        mapper.insertBatch(List.copyOf(records));
    }
}
