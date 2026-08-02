# AI Generation 第二阶段测试运行手册

本文只描述隔离测试、预发布和人工验收。禁止把任何变量指向生产 PostgreSQL、Redis、RabbitMQ、真实付费模型或生产用户。

## 已准备的隔离基础设施

RabbitMQ 延迟插件镜像由 `docker/test/rabbitmq-delayed/Dockerfile` 构建，基础镜像固定为 `rabbitmq:4.1-management`，插件固定为官方 `4.1.0` 发布物及 SHA-256。构建后设置：

```powershell
docker build -t ait-rabbitmq-delayed:4.1.0 docker/test/rabbitmq-delayed
$env:AIT_TEST_RABBIT_DELAYED_IMAGE = 'ait-rabbitmq-delayed:4.1.0'
```

进程内假上游服务位于 `scripts/diagnostics/ai-generation/fake-openai-server.mjs`。它只支持固定模型名，绝不记录请求正文：

| 模型名 | 行为 |
| --- | --- |
| `ait-test-normal` | 两个片段、最终 Usage 和 `STOP` |
| `ait-test-slow` | 延时输出，供 Stop/失联测试 |
| `ait-test-fail-before-first` | 首片前 HTTP 503 |
| `ait-test-fail-after-partial` | 输出首片后中断流 |
| `ait-test-no-usage` | 正常结束但不提供 Usage |

启动命令：

```powershell
$env:AIT_FAKE_OPENAI_PORT = '18317'
node scripts/diagnostics/ai-generation/fake-openai-server.mjs
```

隔离应用实例 A/B 必须分别设置不同的 `AI_CONVERSATION_INSTANCE_ID`，并指向同一个隔离 PostgreSQL、Redis、RabbitMQ 和假模型：

```text
AI_CONVERSATION_ASYNC_GENERATION_ENABLED=true
AI_CONVERSATION_INSTANCE_ID=instance-a 或 instance-b
AI_CONVERSATION_DETACH_GRACE=300ms（自动化）或 30s（发布验收）
AI_INFERENCE_CLI_PROXY_BASE_URL=http://127.0.0.1:18317/v1
```

## 自动化门禁

日常单节点 RabbitMQ：

```powershell
mvn -pl ai-temperate-service -am `
  "-Dtest=RabbitAiConversationGenerationDelayedIntegrationTest,RabbitAiConversationGenerationReliabilityIntegrationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

该组验证 x-delay、Persistent、Confirm、mandatory Return、Quorum、手动 ACK、拒绝进 DLQ、ACK 前断线重投，以及 Owner Control 只进入持有任务实例的队列。

发布前真实三十秒宽限：

```powershell
$env:AIT_TEST_REAL_DETACH_GRACE = 'true'
mvn -pl ai-temperate-service -am `
  "-Dtest=RabbitAiConversationGenerationThirtySecondDetachIntegrationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

发布前三节点 Quorum 故障切换：

```powershell
$env:AIT_TEST_RABBIT_QUORUM_IMAGE = 'ait-rabbitmq-delayed:4.1.0'
mvn -pl ai-temperate-service -am `
  "-Dtest=RabbitAiConversationGenerationThreeNodeQuorumIntegrationTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

此测试停止实际 Queue Leader，在未 ACK 后等待新 Leader，并验证持久消息以 redelivery 方式继续消费。它证明 At Least Once 投递；不把它描述为 Exactly Once。

Redis 双实例与假上游：

```powershell
mvn -pl ai-temperate-service -am `
  "-Dtest=RedisAiConversationGenerationMultiInstanceIntegrationTest,AiConversationGenerationWorkerImplTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test

Push-Location fornted
npm run test:ai-generation-fake-upstream
Pop-Location
```

## 预发布迁移演练

先确认目标是隔离数据库，并且应用开关保持关闭。不得在生产库首次执行本演练。

```powershell
$env:AIT_CONFIRM_ISOLATED_PREPRODUCTION = 'YES_ISOLATED_NON_PRODUCTION'
$env:AI_CONVERSATION_ASYNC_GENERATION_ENABLED = 'false'
$env:PGHOST = '<isolated-host>'
$env:PGPORT = '5431'
$env:PGDATABASE = '<name-containing-test-or-staging-or-preprod>'
$env:PGUSER = '<isolated-user>'
$env:PGPASSWORD = '<isolated-password>'
scripts/diagnostics/ai-generation/invoke-preproduction-migration-rehearsal.ps1
```

脚本只执行 `sql/011_create_ai_conversation_generation.sql`、`sql/012_create_ai_conversation_generation_payload.sql` 及只读的 Schema/孤儿检查；不执行破坏性回滚。验证后启动 A/B 实例，先只给内部测试用户开启功能。

## P95 压测

使用隔离的测试用户、假模型和专用模型 ID。下面的负载脚本先创建 Generation，收到公共 ID 后立即调用用户 Stop 并轮询终态；它不输出 Cookie、CSRF Token、Generation ID、正文或余额。

```powershell
$env:AIT_CONFIRM_ISOLATED_LOAD = 'YES_ISOLATED_NON_PRODUCTION'
$env:AIT_TEST_BASE_URL = 'https://isolated.example.test'
$env:AIT_TEST_USER_COOKIE = '<isolated-test-user-cookie>'
$env:AIT_TEST_CSRF_TOKEN = '<isolated-csrf-token>'
$env:AIT_TEST_CSRF_HEADER_NAME = 'X-CSRF-TOKEN'
$env:AIT_TEST_MODEL_PUBLIC_ID = '<11-character-test-model-id>'
$env:AIT_TEST_SAMPLES = '200'
$env:AIT_TEST_CONCURRENCY = '1'
node scripts/diagnostics/ai-generation/user-stop-load.mjs
```

分别执行单并发、预期生产并发、Rabbit Leader 故障期间和 Redis Pub/Sub 短暂不可用期间。数据库才是权威口径：

```powershell
$env:AIT_CONFIRM_ISOLATED_PREPRODUCTION = 'YES_ISOLATED_NON_PRODUCTION'
scripts/diagnostics/ai-generation/invoke-p95-report.ps1 `
  -TestStartedAt '2026-08-01T00:00:00Z'
```

要求：用户 Stop/管理员取消的 `cancel_requested_at → settled_at` P95 不超过 1 秒；失联的 `detached_at → cancel_requested_at` 为 30～31 秒，随后结算 P95 不超过 1 秒；任何重复资金操作、`RECONCILE_REQUIRED` 或非预期 DLQ 均为零。不同实例执行前必须完成时钟同步。

## H5 人工验收

只能通过已连接扩展的外部 Chrome 操作，禁止 Codex 内置浏览器。每个场景都保存前端关闭/Stop 时刻、Trace ID、Generation ID、最终数据库状态与 Rabbit Ready/Unacked/DLQ 快照：

1. 站内切页、切换会话、浏览器切后台超过一分钟：Generation 继续。
2. 刷新后 30 秒内：恢复同一 Generation、快照和 revision，不创建第二次模型调用。
3. 关闭标签页：约 31～32 秒进入 `CLIENT_EXIT_TIMEOUT` 并结算。
4. 用户 Stop：一秒目标内冻结并结算。
5. 管理员取消：只影响目标 Generation。
6. 临时断网重连：恢复观察者，不创建第二个 Worker。
7. 取消 API 失败：页面只能显示“取消处理中”，不能声称已退款。

## Android 人工验收

必须提供已安装的包名、ADB serial、模拟器以及至少一台真实设备，才可执行。现有 `manifest.json` 只有 UniApp `appid`，不是可供 `adb shell am force-stop` 使用的 Android package name；因此不得猜测包名。

逐项记录：Home 后超过一分钟继续生成、30 秒内返回恢复、Wi-Fi 与移动网络切换恢复、Stop、管理员取消、上游失败、真实设备强制停止后约 31～32 秒取消。若 Android 在后台就暂停网络超过 30 秒，应如实记录为“系统后台网络策略导致失联”，不能误写成用户强杀。

## 当前证据与未执行项

已在本机 Docker/Testcontainers 通过：单节点 RabbitMQ 可靠性 5 项、真实 30 秒延迟、三节点 Quorum Leader 故障切换、Redis A/B Pub/Sub 3 项、Worker 假模型 5 项、Generation PostgreSQL 迁移 5 项、假模型 Node 测试 4 项。

尚未执行：真实预发布数据库迁移、部署态 A/B 应用全链路、200 次 P95、外部 Chrome 实机验收、Android 模拟器/真机验收。它们依赖隔离部署地址、测试认证资料及 Android 包名/设备，不能以本地单元或容器测试替代。
