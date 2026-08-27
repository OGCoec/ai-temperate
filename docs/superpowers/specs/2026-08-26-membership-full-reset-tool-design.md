# Membership 全库重置工具设计

## 目标

提供一个可双击运行的 Windows BAT 入口，将本机开发/压测环境中的 Membership 支付数据恢复为空白状态。工具不要求人工确认，但必须拒绝非本机目标、运行中的应用或压测，以及未排空的 Membership RabbitMQ 队列。

## 组成

- `Reset-AllMembershipData.bat`：用户唯一需要双击的入口，定位仓库并调用 PowerShell 7；保留窗口显示结果和错误码。
- `Reset-AllMembershipData.ps1`：执行前置检查、PostgreSQL 事务、Redis 批量清理和结果验证。
- `Test-ResetAllMembershipDataContract.ps1`：纯离线合同测试，只检查清理范围、安全门禁和 BAT 调用协议，不连接外部服务。

## 固定目标与前置门禁

- PostgreSQL 固定为 `postgresql://postgres@127.0.0.1:5431/ai_temperate`，禁止环境变量把全库清理重定向到远端或其他数据库。
- Redis 固定使用 Docker 容器 `redis7`，复用现有容器密码解析方式，凭据仅通过 `REDISCLI_AUTH` 传给子进程。
- RabbitMQ 固定检查容器 `rabbitmq1`。所有 `membership.*` 队列必须同时满足 `messages_ready=0` 和 `messages_unacknowledged=0`；工具不负责 purge 队列。
- 端口 6655 不得有监听者；Win32 进程命令行中不得存在会员压测 Master、Scheduler、Suite、Wave 或对应 JMeter 进程。
- 必须找到 `pwsh`、`psql`、`docker`，并成功验证 PostgreSQL 当前数据库名、Redis PING 和 RabbitMQ 队列状态后才能修改数据。

## PostgreSQL 重置

使用 `psql -X -w -v ON_ERROR_STOP=1` 执行一个本地事务，顺序固定为：

1. 删除 `membership_payment_callback` 全部记录。
2. 删除 `membership_order` 全部记录。
3. 更新 `user_membership_quota` 全部记录：`membership_tier=0`、`quota_balance_minor=0`，并将 `quota_period_started_at`、`quota_period_ends_at`、`membership_expires_at` 置为 `NULL`。
4. 在提交前验证两张支付表记录数为零，且不存在任何未归零或仍保留周期时间的 quota 记录。

事务输出删除/更新行数。任意 SQL 或验证失败都回滚，不允许数据库半清理。

## Redis 重置

使用 `SCAN MATCH COUNT 500` 枚举下列固定模式，去重后以每批最多 100 个 Key 执行 `UNLINK`：

- `ait:*:payment:membership-order:v[12]:snapshot:*`
- `ait:*:payment:membership-order:v[12]:status:*`（兼容用户指定的旧路径）
- `ait:*:payment:provider-result:v[12]:status:*`（当前代码实际使用的状态路径）
- `ait:*:payment:membership-order:v[12]:callback:*`
- `ait:*:payment:callback:v[12]:data:*`
- `ait:*:payment:callback:v[12]:idem:*`
- `ait:*:payment:callback:v[12]:order-idem:*`
- `ait:*:payment:callback:v[12]:provider-idem:*`
- `ait:*:payment:callback:v[12]:ready:all`
- `ait:*:payment:callback:v[12]:processing:all`
- `ait:*:payment:order-persist:v[12]:dirty:all`
- `ait:*:payment:order-persist:v[12]:processing:all`

不删除 `order-persist:*:lock:*`，因为 Redisson 锁只能由持锁线程释放。应用已停止时遗留锁应由看门狗 TTL 回收。

清理后重新扫描全部模式，任何残留都使脚本失败。数据库已清而 Redis 失败时，脚本可直接重复执行；数据库操作和 `UNLINK` 都是幂等的。

## 输出与错误处理

- 成功输出 PostgreSQL 删除/更新行数、Redis 匹配/删除 Key 数以及最终 `RESET_COMPLETE`。
- 失败输出明确阶段和原因，返回非零退出码；BAT 使用 `pause` 保留窗口。
- 工具不启动或停止任何应用，不清 RabbitMQ 消息，不修改 Java、Lua、XML、JMX、Mapper 或业务配置，也不自动发起压测。

## 验收

- 离线合同先在脚本不存在时失败，再在实现后通过。
- BAT 正确使用自身目录定位 PS1，并传播退出码。
- PowerShell 与测试文件通过语法解析。
- 本次实现阶段不执行 BAT，不连接 PostgreSQL、Redis 或 RabbitMQ；真实清理只由用户之后双击触发。
