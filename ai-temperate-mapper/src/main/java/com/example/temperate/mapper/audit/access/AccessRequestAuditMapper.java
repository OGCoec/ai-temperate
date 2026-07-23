package com.example.temperate.mapper.audit.access;

import com.example.temperate.model.audit.access.AccessRequestAuditRecord;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 提供访问请求审计记录的批量幂等写入和有界过期删除，不负责消息确认或保留期编排。
 */
@Mapper
public interface AccessRequestAuditMapper {

    /**
     * 使用一次批量 SQL 写入一批审计记录，重复消息 ID 由数据库唯一索引忽略。
     */
    int insertBatch(@Param("records") List<AccessRequestAuditRecord> records);

    /**
     * 按发生时间和主键稳定排序删除一批过期记录，防止单次清理形成无界事务。
     */
    int deleteExpiredBatch(
            @Param("cutoff") Instant cutoff,
            @Param("limit") int limit);
}
