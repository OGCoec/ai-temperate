# 会员模拟支付真实 5+5 分钟分层验收

这套验收固定运行 `loadtest-realtime`：`PENDING_PAYMENT` 为 5 分钟，`CLOSING` 为 5 分钟，硬截止时间始终为 `expiresAt + 5 minutes`。七个 JMX 相互独立；JMeter 负责真实 HTTP、并发和按服务端时间等待，Runner 负责 PostgreSQL、Redis、RabbitMQ 的最终业务裁决。总入口与所有子 Runner 都拒绝 `loadtest-fast`。

## 本机测试身份与配置

Runner 不要求手工填写 Access Token、模拟商户号或回调密钥。应用的 `loadtest-realtime` Profile 内置仅测试的商户号和占位回调密钥，并只允许以下四个已有账号：

```text
73014701344296960
72659006262480896
76721355290185728
74891801495998464
```

应用启动后，Runner 调用仅限回环地址的 `POST /internal/test/membership-payments/loadtest-tokens`，用项目现有 JWT 签发器生成短期 Access Token。Token 只写入 Git 忽略的 `loadtest/local/loadtest-users.csv`，不会进入 JTL、HTML 报告、日志或 `loadtest-output`。Runner 不创建或修改账号。

## 七层场景

1. `membership-auth-boundary`：8 类认证、白名单、越权和路径隔离边界。
2. `membership-order-state-machine`：PENDING、CLOSING、CANCELLED、CLOSED、PAID 各至少 5 例，并以数据库实际 `received_at` 判断时间区间。
3. `membership-callback-transport`：GET、POST Form、POST JSON 及格式、签名、金额和方法边界。
4. `membership-callback-race-idempotency`：订单 ID 与第三方流水号双维度顺序/并发去重，关键组合执行 1、10、50、100、500 并发。
5. `membership-rabbit-state-timing`：真实 5+5 分钟、手动 ACK、有限重投、单条预期 DLQ 与 callback marker 竞态。
6. `membership-persistence-batch`：1、99、100、101、500、2000 六组，共 2801 条真实订单经过 callback、dirty set 与 PostgreSQL 批量收敛。
7. `membership-recovery-terminal-cleanup`：callback/dirty processing 恢复、一次性注入“数据库已提交但 Redis complete 前中断”、数据库唯一兜底及 PAID/CANCELLED/CLOSED 终态 Redis 清理。

合法重复回调固定断言 `HTTP 200`、`Content-Type: text/plain`、正文严格为 `success`。内部必须保持 no-op：每个 `order_id` 和 `provider_trade_no` 最多一条 callback；PAID 后续回调不新增 `ALREADY_APPLIED` 行、不触发退款；CANCELLED/CLOSED 的首次迟到成功只产生一次 `REFUND_REQUIRED` 条件，不执行真实退款。

## 执行

应用必须已经用真实时间 Profile 启动：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'loadtest-realtime'

.\loadtest\scripts\run-membership-suite.ps1 -Mode loadtest-realtime
```

Runner 默认连接本机 PostgreSQL `5431`、Redis 容器 `redis7` 和 RabbitMQ 容器 `rabbitmq1`。仅当本机地址或凭据不同才通过环境变量覆盖；密钥和密码不会写入运行产物。

每个场景生成 `loadtest-output/runs/<timestamp>-loadtest-realtime-<scenario>/`，其中包含 JTL、JMeter 日志、HTML 报告、输入 CSV 副本、脱敏用户清单、场景订单清单、可复现命令、时间偏差、SQL 输出、Redis 前后快照与终态工件验收、RabbitMQ 前后快照与拓扑/基线验收、`summary.csv` 和 `verdict.json`。任一 HTTP、SQL、Redis 或 RabbitMQ 裁决失败，Runner 返回非零退出码。

SQL 业务事实通过后，Runner 固定再等待 40 秒，使本测试矩阵里最长 30 秒的“终态后下一条已发布延迟检查”能够到队列并被消费；随后才采集 RabbitMQ 与 Redis 终态基线，避免把延迟交换机中暂不可见的消息遗留给下一场景。

Redis 验收只使用 `SCAN` 检查禁止出现的 Stream，并通过应用复用 `RedisKeyFactory` 分批检查精确订单；每批最多 250 个订单、500 条 Pipeline 命令，不使用 `KEYS *` 或逐订单网络 I/O。终态订单的 snapshot 和 callback marker 必须消失；带明确 TTL 的小型幂等键可以短暂保留。

RabbitMQ 验收要求业务队列 durable Quorum、Exchange durable、消息 persistent、消费者手动 ACK、有限重投，且 Ready/Unacked 返回运行前基线。Rabbit 专项场景会有且仅有一条受控 poison probe 进入 payment DLQ，其他场景不允许新增非预期 DLQ 消息。

默认不删除测试订单。只有显式传入 `-Cleanup` 才按本次 `scenario-orders.csv` 中的精确订单 ID 清理，并且仅允许在启用 `loadtest-realtime` 时执行。

七层完成后，关闭 `MEMBERSHIP_PAYMENT_LOADTEST_ENABLED` 并重启应用，再执行：

```powershell
.\loadtest\scripts\assert-membership-loadtest-disabled.ps1 `
  -BaseUrl 'http://localhost:8080'
```

关闭开关检查默认复用 Runner 已写入本地受限 CSV 的第一个短期 Token，不需要手工复制；也可以显式传入 `-AccessToken` 覆盖。

最终链路仍然只是受控模拟支付：不接真实六号支付、不升级会员权益、不执行真实退款、不创建 Redis Stream。
