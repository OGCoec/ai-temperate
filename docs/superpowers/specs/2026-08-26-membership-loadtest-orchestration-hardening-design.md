# 会员订单压测编排生命周期与正式启动窗口加固设计

## 1. 背景

当前会员订单创建压测由 Master、Scheduler、Suite、Wave 和 JMeter/Groovy 五层编排组成，并在同一个应用进程上依次执行 Canary、40K 和 80K。现有流程已经具备逐区段双预热、精确清理、服务端 HTTP 墙钟、黄金基线对照、PostgreSQL 稳定性门禁和运行状态原子写入能力。

本设计解决两个剩余的高风险编排问题：

1. 子运行退出后，Master 在完成大量报告验证之后才记录被保留应用的 PID。如果报告读取或验证在接管前失败，Master 无法可靠回收应用，可能留下孤立 JVM、固定端口占用和未归档日志。
2. 预热②清理结束后的十秒正式启动窗口目前包含源码指纹、健康检查、基础设施快照、Token 生成、JMeter JVM 启动、Groovy 加载及二十五个串行 TEAM 负向探针。这会把编排准备延迟错误地计入热态启动合同，并可能在 Java 业务正常时触发 `FORMAL_START_DEADLINE_EXPIRED`。

本设计只修改外部 PowerShell/Groovy/JMeter 编排与合同测试，不修改 Java、Lua、Mapper XML、业务 YAML、SQL、公开 API 或业务状态机，也不重新打包 JAR。

## 2. 设计目标

- 子运行结束后，Master 必须在读取任何业务报告前取得或确认应用生命周期责任。
- 任意报告解析、证据验证或后续阶段启动失败时，应用都能被准确识别、停止并按现行策略归档日志。
- 继续使用同一个应用 PID 完成 Canary、40K 和 80K，不因加固而重启应用或改变热态。
- 保留“预热②精确清理完成到服务端收到首个正式 ORDER_CREATE 请求不超过十秒”的硬合同。
- 将不会改变正式业务数据的准备动作移到预热②清理之前，并在清理成功后通过原子信号释放已经准备好的 JMeter。
- 正式 ORDER_CREATE 必须先于 TEAM 负向探针执行，完整创建墙钟不得被负向探针污染。
- Sampler 存活判断不再依赖 Windows 文件修改时间，而使用原子心跳序号和父进程单调计时。
- 冻结并记录实际调用的 PowerShell、JDK、JMeter、Git 和 psql 工具链身份。
- 继续保持所有功能、可靠性、数据一致性和环境失败的现有停止语义。

## 3. 非目标

- 不改变 5K、10K、40K、80K 的数量、区段顺序或边界时间规则。
- 不改变创建并发 256、支付并发 56、共享 HTTP 许可 256、回调并发 256、Redis `64×6` 和逻辑在途 384。
- 不放宽 PostgreSQL、Redis、RabbitMQ、Hikari、HTTP 和收敛门禁。
- 不把十秒门槛延长为三十秒，也不允许第三次预热或超时后自动重试当前正式区段。
- 不恢复中断预热；中断仍必须使用新 Run ID 从当前区段预热①重新开始。
- 不在本设计阶段运行合同测试、启动服务、清理数据或产生压测负载。

## 4. 总体架构

### 4.1 应用生命周期状态

应用生命周期使用以下显式状态：

```text
CHILD_OWNED
→ OFFERED
→ MASTER_OWNED
→ BORROWED_BY_CHILD
→ MASTER_OWNED
→ STOPPING
→ RELEASED
```

- 首次 Canary 启动应用后，应用由 Scheduler 子进程持有，状态为 `CHILD_OWNED`。
- 子运行完成全部功能和证据门禁后，以原子文件发布 `OFFERED`。
- Master 验证进程身份并原子记录后，状态变为 `MASTER_OWNED`。
- 40K/80K 子运行复用应用时只是借用，不改变最终所有者；子运行结束后仍回到 `MASTER_OWNED`。
- 最终阶段正常停止或任意失败回收时，状态依次变为 `STOPPING` 和 `RELEASED`。

PID 本身不能证明所有权。所有停止动作必须同时验证 PID、进程启动时间、固定 JAR 路径、JAR SHA-256 和 6655 监听所有者。

### 4.2 正式区段启动状态

每个区段在预热②完成后使用以下状态：

```text
WARMUP_2_VERIFIED
→ FORMAL_PREPARING
→ FORMAL_ARMED
→ WARMUP_2_RESETTING
→ FORMAL_RELEASED
→ FORMAL_RUNNING
→ FORMAL_SETTLING
```

`FORMAL_ARMED` 表示 JMeter 已启动、Groovy 已加载、正式用户文件已验证，但尚未发送订单创建、支付、回调或 TEAM 探针请求。只有预热②精确清理回执全部通过后，Suite 才能发布 `FORMAL_RELEASED` 信号。

## 5. 应用 PID 所有权交接

### 5.1 子进程交接文件

`Start-MembershipSchedulerIndexHikariRetest.ps1` 在允许应用继续运行前，原子写入当前子运行根目录下的：

```text
application-handoff.json
```

文件至少包含：

```text
schemaVersion
handoffState = OFFERED
masterRunId
childRunId
applicationPid
processStartedAt
jarPath
jarSha256
port = 6655
stdoutPath
stderrPath
timingLogPath
httpEvidenceLogPath
offeredAt
```

交接顺序固定为：

```text
完成全部子阶段功能与证据验证
→ 提前读取并保存最终结果内容
→ 验证应用仍存活且身份未变化
→ 原子写入 application-handoff.json
→ 将 keepApplicationOnExit 设为 true
→ 返回已经准备好的最终结果
```

只要原子交接文件没有成功发布，应用仍归子进程所有，`finally` 必须停止应用并归档日志。交接后的任何输出错误不能使应用失去所有者；Master 无论子进程退出码是否为零，都必须先检查交接文件，再处理退出结果。

### 5.2 Master 接管顺序

`Start-MembershipOrderCreateOptimizationRetest.ps1` 在子进程退出后执行：

```text
清除 currentChildPid
→ 检查 application-handoff.json
→ 验证应用身份
→ 原子记录 Master 所有权
→ 更新 Master heartbeat/run-state
→ 检查子进程 ExitCode
→ 读取 verdict、QPS、黄金对照和清理回执
```

Master 的所有权记录写入：

```text
master-application-ownership.json
```

记录至少包含：

```text
ownershipState
ownerMasterRunId
sourceChildRunId
applicationPid
processStartedAt
jarPath
jarSha256
port
descriptorPath
handoffPath
acceptedAt
```

只有所有权记录原子发布后，Master 才能继续业务报告验证。此后的任意异常都由 Master 的失败路径负责停止应用和归档日志。

如果子进程异常退出且没有有效交接文件，Master 仍要读取该子运行的 `application-start.json` 和最后一份原子心跳进行残留审计。只有 PID、进程启动时间、固定 JAR、JAR 哈希和 6655 监听者全部匹配时，Master 才能把该进程作为异常残留紧急停止；该路径不得把应用接管后继续用于下一个阶段，整体必须判定 `TEST_INVALID_APPLICATION`。身份不能完整证明时禁止停止进程，并在失败证据中明确记录需要人工确认的 PID。

### 5.3 复用与最终释放

- Canary 成功后，Master 接管首次启动的应用。
- 40K 子运行收到现有 PID、进程启动时间和描述文件，只能借用该应用；进入和退出时都必须验证其身份没有变化。
- 80K 正常完成并由子运行停止应用后，Master 必须立即验证进程已经退出、日志已经归档，并清空所有权状态，而不是等到全部汇总报告结束后才清空。
- 如果子运行声称已经停止应用但进程仍存活，判定 `TEST_INVALID_APPLICATION`，由 Master 执行受验证的紧急回收。

### 5.4 受验证的停止与日志归档

应用生命周期操作集中到一个共享 PowerShell 模块，避免 Master 和 Scheduler 使用不同的判断方式。模块职责限定为：

```text
Get-VerifiedLoadtestApplication
Accept-ApplicationHandoff
Stop-VerifiedLoadtestApplication
Archive-VerifiedApplicationLogs
```

停止前必须验证：

- PID 与记录一致。
- Windows 进程启动时间与记录一致，防止 PID 被复用。
- 命令行指向固定 JAR。
- 固定 JAR SHA-256 与 Master 冻结值一致。
- 6655 的唯一监听者是该 PID。

身份不一致时禁止停止该 PID，并以 `TEST_INVALID_APPLICATION` 报告需要人工处理的疑似孤立进程。

停止及归档顺序固定为：

```text
验证身份
→ 停止进程并等待退出
→ 复制 stdout/stderr/timing/HTTP 证据日志
→ 计算并核对归档 SHA-256
→ 删除已经校验归档的固定源日志
→ 原子写入 formal-log-archive-manifest.json
→ 所有权状态改为 RELEASED
```

归档失败时应用仍应先停止，但源日志不得删除，整体判定为 `TEST_INVALID`。

## 6. 十秒正式启动窗口

### 6.1 准备动作前移

预热②功能及证据报告完成、精确清理尚未开始时，正式 Wave 先完成以下工作：

```text
检查冻结的源码、JAR和工具链身份
→ 检查应用、PostgreSQL PID及运行配置
→ 创建正式输出路径和Manifest
→ 生成本正式轮独立Token文件
→ 校验Token数量、顺序和文件SHA-256
→ 启动JMeter JVM
→ 加载JMX和Groovy
→ 读取正式用户CSV
→ 初始化HttpClient、并发许可和输出Writer
→ 发布 formal-driver-ready.json
→ 等待开始信号
```

Token 端点调用允许发生在准备阶段，但在收到开始信号前禁止发送 ORDER_CREATE、PAYMENT_ATTEMPT、Callback 和 TEAM 负向探针。

### 6.2 JMeter 预武装协议

Wave 脚本拆分为“准备”和“完成”两个受同一描述文件约束的阶段。准备阶段启动 JMeter 并记录：

```text
formal-prepared-wave.json
```

Groovy 完成初始化后原子发布：

```text
formal-driver-ready.json
```

两个文件包含一致的 Run ID、Group Code、JMeter PID、准备 Nonce、JMX/Groovy/Token 哈希和输出路径。Suite 只有在 JMeter 进程存活、ready 文件完整且所有身份字段匹配时，才能开始清理预热②。

JMeter 在等待期间必须设置有界超时。准备失败、清理失败、父 Suite 退出或开始信号超时，都必须终止该 JMeter 进程树并保留诊断日志。

### 6.3 清理与开始信号

JMeter 进入 `FORMAL_ARMED` 后执行预热②精确清理：

```text
删除当前预热清单中的回调
→ 删除当前预热清单中的订单
→ 恢复当前区段额度为FREE
→ 批量UNLINK当前清单Redis状态
→ 验证当前组订单和回调为0
→ 验证既有正式数据完整保留
→ 验证Redis和Rabbit连续归零
→ 原子写入清理回执及归零快照
→ 最后记录 cleanupCompletedAt
```

正式前 Redis/Rabbit 基线直接引用清理回执中的归零快照及其哈希，不在十秒窗口内重复执行慢速快照。

Suite 随后立即计算：

```text
deadline = cleanupCompletedAt + 10秒
```

并原子写入：

```text
formal-start-signal.json
```

文件至少包含：

```text
runId
groupCode
nonce
cleanupCompletedAtEpochMillis
deadlineEpochMillis
cleanupReceiptSha256
state = RELEASED
```

Groovy 必须验证 Run ID、区段、Nonce、清理回执哈希和截止时间，验证成功后立即提交正式 ORDER_CREATE 并发任务。

### 6.4 TEAM 探针顺序

二十五个 TEAM 负向探针从创建前移动到完整创建阶段之后：

```text
释放正式开始信号
→ 完成全部5K/10K ORDER_CREATE及支付发起
→ 验证创建数量完整
→ 串行执行恰好25个TEAM负向探针
→ 等待边界Callback
→ 最终收敛和报告
```

探针不能只移动到首个创建请求之后，否则仍会与剩余创建请求竞争 CPU、连接和线程，污染完整创建墙钟。探针仍属于正式功能硬门禁，数量或状态错误必须判定正式功能失败。

### 6.5 十秒裁决口径

十秒合同继续使用服务端证据：

```text
firstFormalReceivedAt = min(正式ORDER_CREATE receivedAtEpochMicros)
gap = firstFormalReceivedAt - cleanupCompletedAt
PASS条件：0 ≤ gap ≤ 10秒
```

客户端开始发送时间、JMeter启动时间和信号发布时间都不能替代服务端接收时间。预武装完成后仍超过十秒，判定 `TEST_INVALID`，黄金基线不裁决；不得延长门槛、执行第三次预热或自动重试正式段。

## 7. Sampler 存活协议

`Measure-MembershipPaymentRuntimeEvidence.ps1` 每次成功采样后原子写入：

```text
evidence-sampler-heartbeat.json
```

字段至少包含：

```text
samplerPid
sequence
sampleStartedAt
lastSuccessfulSampleAt
currentGroupCode
warmupAttempt
lastCompletedEvidenceSet
```

Scheduler 不再使用 CSV 的 `LastWriteTime` 判断存活。它在观察到 `sequence` 推进时，用自己的 `Stopwatch` 单调计时器记录最近推进点：

- Sampler 进程退出时立即失败。
- 正式预热或负载阶段连续十秒没有序号推进时判 `TEST_INVALID`。
- PostgreSQL 120 秒稳定性门禁阶段允许十五秒，但仍要求 Sampler 进程存活且最终样本连续。
- 心跳 JSON 缺失、无法解析或序号倒退均判 `TEST_INVALID`。

该变化只防止 Windows 文件时间和短暂磁盘刷新延迟造成误判，不取消采样完整性门禁。

## 8. 工具链和冲突进程冻结

Master 初始预检原子写入：

```text
toolchain-lock.json
```

记录并冻结：

- `pwsh.exe` 完整路径和版本。
- 应用 JDK 的 `java.exe` 完整路径、版本和哈希。
- JMeter 完整启动路径、版本、核心 JAR 哈希、实际使用的 JDK 和堆参数。
- `psql.exe` 和 `git.exe` 的完整路径与版本。
- 固定业务 JAR 路径及当前 Master 冻结的 SHA-256。

所有子脚本必须使用 Master 传入的显式路径；阶段之间重新解析到其他可执行文件时判 `TEST_INVALID_ARTIFACT`。

冲突 JVM 检查同时覆盖 `java.exe` 和 `javaw.exe`，但只根据固定 JAR或压测 JMeter 命令行认定冲突，不得影响无关 Java 程序。

Master Run ID 长度不再使用孤立常量。启动前根据最长子阶段后缀、最长区段代码、固定流水号前缀和十七位用户 ID 计算最大安全长度，确保 Provider Trade Number 始终不超过 128 字符。

## 9. 错误处理

| 事件 | 处理 |
| --- | --- |
| 子进程无交接文件且退出失败 | 子进程正常情况下负责停止；Master执行受验证的残留审计，发现匹配残留时紧急停止并判测试无效 |
| 子进程退出失败但存在有效交接 | Master先接管，再按失败路径停止和归档 |
| 交接PID身份不一致 | 禁止误杀；判 `TEST_INVALID_APPLICATION` |
| Master接管后报告损坏 | Master停止应用、归档日志并判测试无效或对应原始失败 |
| JMeter预武装失败 | 精确清理预热②，停止JMeter，不发送正式信号 |
| 预热②清理失败 | 停止已武装JMeter，停止当前Run，不进入正式段 |
| 开始信号缺失或不匹配 | JMeter不发送任何正式业务请求并有界退出 |
| 服务端首请求超过十秒 | `TEST_INVALID`，黄金基线不裁决，不重试 |
| TEAM探针失败 | 正式功能FAIL，保留正式证据并停止后续区段 |
| Sampler心跳停滞 | `TEST_INVALID`并停止 |
| 工具链、源码、JAR或PID漂移 | `TEST_INVALID`并停止 |

## 10. 合同测试

### 10.1 应用生命周期

1. 子进程成功后，Master 在任何业务报告读取前接管应用。
2. 接管后制造 QPS 或黄金报告解析失败，应用仍被停止并完成日志归档。
3. 子进程非零退出但存在有效交接文件时，Master仍先接管再回收。
4. 交接文件未原子完成时，子进程 `finally` 停止应用。
5. PID存在但进程启动时间不一致时禁止停止该进程。
6. Canary和40K复用完全相同的PID及启动时间。
7. 80K结束后应用停止，Master所有权状态为 `RELEASED`。
8. 日志归档失败时源日志保留且整体判 `TEST_INVALID`。

### 10.2 正式预武装

1. 预热②清理前 JMeter 已发布匹配的 `formal-driver-ready.json`。
2. `FORMAL_ARMED` 阶段不存在创建、支付、回调或TEAM请求。
3. JMeter未ready时不得清理预热②。
4. 清理回执未通过时不得发布开始信号。
5. 原子开始信号后第一类正式业务请求必须是 ORDER_CREATE。
6. 服务端首个接收时间距离清理完成不超过十秒。
7. 缺失、损坏、过期或Nonce不一致的开始信号不会产生业务请求。
8. 二十五个TEAM探针在完整创建阶段结束后执行且仍为功能硬门禁。
9. 准备失败、清理失败和父进程退出都会回收预启动JMeter进程树。
10. 5K和10K分别使用独立Token、订单、Trace和场景清单。

### 10.3 Sampler与工具链

1. 原子Sampler心跳序号正常递增。
2. 进程退出、心跳停滞、序号倒退和JSON损坏分类正确。
3. Windows文件修改时间不再参与Sampler存活判断。
4. `java.exe` 与 `javaw.exe` 中的固定压测应用都可被发现。
5. 工具路径、版本或哈希漂移在产生负载前停止。
6. 自动生成和最长允许的Master Run ID都满足128字符流水号边界。

所有合同测试只使用临时文件、模拟进程描述和静态脚本分析，不连接 PostgreSQL、Redis、RabbitMQ，不启动固定 JAR，也不产生真实负载。真实 Canary、40K 和 80K 必须在合同测试另行获批并通过后执行。

## 11. 涉及文件

计划修改：

- `loadtest/scripts/Start-MembershipOrderCreateOptimizationRetest.ps1`
- `loadtest/scripts/Start-MembershipSchedulerIndexHikariRetest.ps1`
- `loadtest/scripts/Start-MembershipMillisecondBoundarySuite.ps1`
- `loadtest/scripts/Invoke-MembershipMillisecondBoundaryWave.ps1`
- `loadtest/scripts/Measure-MembershipPaymentRuntimeEvidence.ps1`
- `loadtest/scripts/jmeter/membership-millisecond-boundary.groovy`
- 对应 `loadtest/scripts/tests/` 下的合同测试

可以新增一个只负责应用身份、交接、停止和日志归档的共享 PowerShell 模块，以及一个负责预武装正式 Wave 的窄职责脚本。不得借此重构无关压测代码。

## 12. 验收标准

设计实施完成后，静态和模拟合同测试必须证明：

- 应用从启动到最终停止的每一时刻都有唯一明确所有者。
- Master接管发生在任何可能失败的子报告验证之前。
- 后续任意失败都不会留下固定JAR JVM、JMeter进程树或未处理的固定日志。
- 十秒合同没有放宽，且十秒窗口内不再执行源码扫描、Token生成、JMeter启动和TEAM探针。
- 正式首请求由服务端 `receivedAtEpochMicros` 证明。
- TEAM探针不污染完整ORDER_CREATE墙钟。
- Sampler可靠性门禁保留，但不再依赖Windows文件修改时间。
- Java业务代码、固定并发、基础设施配置和测试规模均未改变。
