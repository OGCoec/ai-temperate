package com.example.temperate.service.admin.login;

import java.time.Instant;

/**
 * 定义管理员十分钟登录 Flow 的原子创建、校验和一次性消费边界。
 */
public interface AdminLoginFlowStore {

    void create(AdminLoginFlow flow);

    AdminLoginFlow getRequired(ProtectedAdminLoginAccess access, Instant now);

    void consume(ProtectedAdminLoginAccess access);
}
