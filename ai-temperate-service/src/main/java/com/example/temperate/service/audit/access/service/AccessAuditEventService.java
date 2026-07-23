package com.example.temperate.service.audit.access.service;

import com.example.temperate.service.audit.access.command.AccessAuditCommand;

/**
 * 编排受保护请求完成事件的 IP 脱敏和异步投递，并保证审计故障不改变原业务响应。
 */
public interface AccessAuditEventService {

    void record(AccessAuditCommand command);
}
