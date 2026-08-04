# 普通用户 TOTP 上线说明

## 必需配置

部署前必须由 Secret 管理系统提供：

```text
TOTP_ENCRYPTION_ACTIVE_KEY_ID=v1
TOTP_ENCRYPTION_KEY_BASE64=<规范 Base64 编码的独立 32 随机字节>
```

该 AES-256-GCM 密钥不得与 JWT、Redis HMAC、Edge Proxy、数据库或第三方供应商密钥复用。生产配置没有
默认密钥；缺失或解码后不是 32 字节时应用必须拒绝启动。`TOTP_ISSUER` 是认证器显示名称，默认
`AI Temperate`，变更只影响新生成的配置 URI。

## 数据库边界

基础建表脚本只增加以下两个字段，不增加 TOTP 索引，也不增加新表或物理外键：

```sql
totp_enabled BOOLEAN NOT NULL DEFAULT FALSE,
totp_secret_encrypted VARCHAR(512)
```

上线前应确认历史用户全部为 `false/NULL`。应用使用状态与旧密文双条件 CAS 开启或轮换；关闭使用单条
SQL 同时写入 `false/NULL`。不得把前端展示的 Base32 明文直接写入数据库。

## 流程与回滚

1. 第一因子成功后读取 `totp_enabled`；关闭时继续原登录，开启时只创建五分钟 Redis 挑战。
2. 开启或轮换先生成 32 随机字节，以密文放入 Redis 十分钟；确认新动态码后才写 PostgreSQL。
3. 状态事务提交后撤销全部 Refresh Session；既有 Access Token 最长保留十分钟。
4. 应用版本回滚前必须先停止新的 TOTP 开启入口；旧版本若不能识别 `totp_enabled`，不得直接回滚到会绕过第二因子的登录代码。
5. 加密主密钥丢失会使已启用用户无法完成 TOTP 登录；上线前必须验证 Secret 备份与恢复流程。当前实现只读取活动 Key ID，轮换主密钥前必须先实现旧 Key 解密窗口。

## 第二阶段验证候选

进入用户明确批准的安全测试阶段后，再验证 RFC 6238 向量、三十秒边界、前后一个时间片、五次失败上限、
Redis 并发防重放、十分钟 setup 过期、并发 CAS、数据库回滚、全部 Refresh Session 撤销、H5 HttpOnly
Cookie 路径、AndroidKeyStore 流程保存和前端本地二维码。测试必须使用隔离 PostgreSQL、Redis 与非生产账号。
