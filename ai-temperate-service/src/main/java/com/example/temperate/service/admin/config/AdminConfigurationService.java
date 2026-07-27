package com.example.temperate.service.admin.config;

/**
 * 定义单管理员配置状态检查、首次原子初始化和密码哈希升级边界。
 *
 * <p>调用方不得通过文件是否存在自行推断状态；所有注册和登录决策都必须依赖本接口。</p>
 */
public interface AdminConfigurationService {

    AdminConfigurationSnapshot inspect(boolean forceReload);

    AdminConfiguration requireActive();

    void requireUninitialized();

    void initialize(AdminConfiguration configuration);

    void upgradePasswordHash(String expectedCurrentHash, String upgradedHash);
}
