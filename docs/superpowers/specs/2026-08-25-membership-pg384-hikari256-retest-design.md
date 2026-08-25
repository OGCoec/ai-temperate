# Membership Payment PostgreSQL 384 / Hikari 256 正式复测设计

## 目标

执行一轮新的会员支付 `8 × 5,000 = 40,000` 正式复测。新运行合同取代上一轮 PostgreSQL 100 / Hikari 96 专项合同：

- PostgreSQL `max_connections=384`。
- Hikari `maximumPoolSize=256`、`minimumIdle=8`。
- 创建并发 256、回调并发 256。
- Callback 与 OrderPersist 均为 `100 × 50`。
- 八个区段之间固定间隔 60 秒，每次正式预检固定 120 秒。
- 单一 `loadtest-realtime` 本机应用实例，仅使用 `LOCAL_SIMULATOR`。

本轮只能裁决 Hikari 256，不能继续输出 Hikari 96 的专项结论，也不能与 PostgreSQL 100 / Hikari 96 结果直接等价比较。

## 配置与启动边界

PostgreSQL 只允许操作端口 5431 对应的实例：

- 二进制目录：`C:\Users\damn\Desktop\postgresql\bin`。
- 数据目录：`C:\Users\damn\Desktop\postgresql\data`。
- 监听地址：`127.0.0.1`。
- 目标端口：`5431`。

设置 `max_connections=384` 后必须重启该实例并通过独立 `psql` 会话验证。端口 5430 的另一个 PostgreSQL 实例不在范围内，禁止停止、重启或修改。

应用全局 YAML 默认值保持不变。本轮由正式编排向本轮拥有的 Java 进程显式传入：

- `POSTGRES_POOL_MAXIMUM_SIZE=256`。
- `POSTGRES_POOL_MINIMUM_IDLE=8`。

运行指标必须再次证明实际值为 256/8；环境变量、运行指标或报告合同任一不一致，本轮立即停止。

## Navicat 只读观察合同

Navicat 可以保持打开并查看、刷新表，但属于正式声明的观察器：

- 最多允许 8 个 `application_name=Navicat` 的连接。
- 禁止 INSERT、UPDATE、DELETE、MERGE、DDL、VACUUM、ANALYZE 和手工锁表。
- 禁止保持未提交事务。
- 其他未知客户端连接仍不允许进入正式运行。
- 采样证据记录 Navicat 总连接、active、idle-in-transaction 和最近查询类型。

出现写 SQL、DDL、未提交事务、超过 8 个连接或无法辨认的客户端时，立即停止并判定“测试无效：环境不符合合同”。Navicat 查询本身会消耗 CPU、I/O 和连接，因此最终性能结论明确适用于“包含声明过的 Navicat 只读观察负载”的环境。

## 编排、采样与裁决

正式编排参数化 PostgreSQL 上限、Hikari max/minIdle 和观察器预算，避免把 384/256/8 只写进启动命令而让报告继续使用 100/96/8。

采样器继续记录 Redis、RabbitMQ、PostgreSQL、Hikari、应用 CPU、线程和系统 Context Switch，并增加 Navicat 观察事实。Hikari 专项使用以下硬门禁：

- Hikari timeout 必须为 0。
- 不得出现 `Connection is not available`、`too many clients` 或保留连接槽错误。
- PostgreSQL 客户端连接峰值必须严格小于 384。
- 管理采样连接必须持续成功。
- pending 不得持续增长；连接获取不能成为数据库耗时的主要部分。

调度专项和最近 PAID 部分索引专项保持原合同不变。最终报告标题、摘要、JSON 字段和中文结论统一写成 Hikari 256。

## 测试与执行顺序

1. 先为参数化门禁、启动环境、Hikari 裁决、报告标签和 Navicat 观察合同补充失败测试。
2. 实现最小参数化改动并运行对应 PowerShell 夹具。
3. 重新运行原 43 个定向测试以及本轮新增的失败复位和请求策略测试。
4. 重新打包唯一可执行 JAR并冻结源码与产物指纹。
5. 确认 PostgreSQL、Redis、RabbitMQ 和 40,000 个 FREE quota 均为干净基线。
6. 启动新的正式 runId，执行完整 `8 × 5,000`，持续监督到最终报告完成。
7. 失败时保留已生成证据；只清理由该失败运行清单精确拥有的数据，再修复并重测。

## 回滚

本轮结束后不自动恢复 PostgreSQL 连接上限，也不自动清理正式 40K 数据。只有用户另行明确授权时，才把 5431 实例恢复到原值 100并重启。应用 YAML 默认值未修改，因此退出本轮启动进程后不会遗留 Hikari 256 的全局默认配置。
