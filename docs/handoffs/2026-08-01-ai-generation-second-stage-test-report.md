# AI Generation 第二阶段隔离测试报告

测试日期：2026-08-01。所有已执行基础设施均为本机 Docker/Testcontainers 临时容器或本地 Node 进程；未连接生产 PostgreSQL、Redis、RabbitMQ、真实付费模型或生产用户。

## 通过

| 范围 | 证据 | 结果 |
| --- | --- | --- |
| RabbitMQ 延迟镜像 | Dockerfile 契约与本机构建 `ait-rabbitmq-delayed:4.1.0` | 通过 |
| RabbitMQ 单节点可靠性 | 延迟、Confirm、mandatory Return、Persistent、Quorum、手动 ACK、DLQ、ACK 前断线重投 | 6 项通过（延迟 1，可靠性 5） |
| 真实失联宽限 | 29 秒前不可消费，30～31.5 秒到达 | 1 项通过 |
| RabbitMQ 三节点 | 三副本 Quorum，停止实际 Leader，未 ACK 消息 redelivery，节点恢复后队列收敛 | 1 项通过 |
| Redis A/B | A 实时输出，B Pub/Sub 接收；停订期间丢消息后从单个 Generation Hash 快照/revision 恢复；损坏消息忽略 | 3 项通过 |
| Owner 定向取消 | 请求按 Owner routing key 只进入 instance-a，只有 A Registry 收到取消 | 已包含在 RabbitMQ 可靠性第 5 项 |
| Worker 假模型 | 启动前取消、正常 Usage、部分输出后上游失败、无 Usage、取消与最终 Usage 竞争 | 5 项通过 |
| Generation 迁移 | 两表、无外键、活动任务唯一、`SKIP LOCKED`、终态 CAS、索引与中文 Comment | 5 项通过 |
| H5 逻辑回归 | 全局 Generation Manager、页面隐藏不取消、SSE 断开只重连/DETACHED 等 | 56 项通过 |
| 假 OpenAI 上游 | 正常 Usage、无 Usage、首片前失败、部分输出后断流 | 4 项通过 |

## 环境阻塞，未视为代码失败

在最后一次把所有 Maven 测试合并重跑时，Maven 在读取项目模型阶段尝试从阿里云镜像下载 `spring-boot-starter-parent:3.5.5`，当前受限网络拒绝 socket 调用。离线模式也确认该父 POM不在当前可用缓存中，因此测试没有进入编译或执行阶段。随后尝试用全新本机 PostgreSQL 15 容器单独执行新增 Schema 检查 SQL 时，当前沙盒拒绝访问 Docker Desktop npipe；容器没有启动，也没有修改任何已有数据库。

这不改变上表已完成的独立 Maven/Testcontainers 证据；但也不能将“合并重跑”记为通过。网络恢复或提供该父 POM缓存后，应使用 `docs/operations/ai-generation-second-stage-test-runbook.md` 中的组合命令重新执行。

## 已准备、尚未执行

- 隔离预发布数据库的 `011/012` 真迁移演练及孤儿检查。
- 两个实际应用实例连接同一套隔离 PostgreSQL、Redis、RabbitMQ 和假上游的完整链路。
- 每场景 200 样本的数据库权威 P95。
- 外部 Chrome H5 实机验收。必须使用已连接扩展的外部 Chrome，不使用 Codex 内置浏览器。
- Android 模拟器和真实设备验收。还需要明确 Android package name 与设备 serial，不能从 UniApp appid 猜测。

相关命令、前置变量、数据脱敏边界和验收清单位于 `docs/operations/ai-generation-second-stage-test-runbook.md`。
