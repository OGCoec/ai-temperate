# ADR：API Key 计数 Bloom 固定 v1 与 Leader 重建

- 状态：已接受
- 日期：2026-08-13
- 范围：外部 API Key 认证摘要 Bloom

## 背景

项目通用规范要求计数 Bloom 使用 `BUILDING -> READY -> ACTIVE` 状态和双版本切换，以便重建期间旧版本继续服务。阶段 S 的 API Key 认证由用户明确选择固定 `v1` 命名空间，并要求应用集群启动后由唯一 Leader 清空并重建。该决定减少 Redis 版本元数据、切换与回收逻辑，但固定版本清理和重建期间不能安全地依赖旧 Bloom 判断“肯定不存在”。

Bloom 只用于避免明显无效摘要穿透到 PostgreSQL，不是 API Key 的真实数据来源。认证最终仍由正向/负向缓存和 PostgreSQL 数据决定；预扣事务还会再次锁定并校验 Key、账号、模型及授权映射。

## 决策

API Key Bloom 固定使用以下命名空间，不保存动态版本或 generation 作为认证选择依据：

```text
ait:<env>:bloom:uak:v1:meta
ait:<env>:bloom:uak:v1:bucket:0000
ait:<env>:bloom:uak:v1:receipt:0000
ait:<env>:bloom:uak:v1:leader
ait:<env>:bloom:uak:v1:fence
```

状态机保留 `BUILDING`、`READY`、`ACTIVE` 和 `DEGRADED`：

- 只有 `ACTIVE` 可以返回 `DEFINITELY_NOT_PRESENT` 或 `MAYBE_PRESENT`。
- `BUILDING`、`READY`、`DEGRADED`、元数据缺失和 Redis 异常统一返回 `UNAVAILABLE`。
- 业务收到 `UNAVAILABLE` 必须 Fail Open 回源 PostgreSQL，禁止据此拒绝 API Key。

多实例启动时通过可续租 Redis Leader Lease 选出唯一构建者。每次成功获取 Lease 时，Lua 先递增持久的 `fence` epoch，并把 `epoch:随机租约值` 作为当前 Leader 的精确身份。清理、初始化、批量装载、mutation 恢复、`READY`、`ACTIVE` 和构建降级写入都必须在同一个 Lua 内同时校验当前 Lease 值和元数据中的 `build_fence`；失租的旧 Leader 不得继续修改共享固定 v1，也不得覆盖新 Leader 的状态。

Leader 分批 `UNLINK` 固定 v1 的 Meta、Bucket 和 Receipt，写入 `BUILDING`，按主键分页读取 PostgreSQL，每批最多 500 个当前启用且未过期的 HMAC 摘要，并通过 Pipeline 提交有界 Lua 更新。每批写入前续租，Lua 仍逐次校验 fence，不能把续租成功当成后续命令永久有效。完成后核对元素计数、Bucket 长度和 Receipt，再以受 fence 保护的 Lua 先切换 `READY`、最后原子检查未完成 mutation 并切换 `ACTIVE`。校验失败、计数器溢出/下溢、元数据损坏或 Redis 写入异常都切换为 `DEGRADED`。

为避免“数据库提交成功、Bloom 更新前进程崩溃”造成假阴性，创建 Key、重新启用 Key或延长过期时间使其重新有效之前，必须先用 Lua 在 Hash 中登记 `mutationId -> HMAC 摘要标识` 并使 Bloom 暂时进入 `DEGRADED`。数据库回滚时撤销登记；提交成功后原子增加计数并结束 mutation。只在所有 mutation 完成且结构校验通过时通过 Lua CAS 恢复 `ACTIVE`。进程崩溃留下的 mutation 会令状态持续 `DEGRADED`；Leader 重建时使用 `HSCAN` 每批最多 500 条重放这些受保护摘要，并在受 fence 保护的 Lua 中分批确认。扫描期间新增或未被本轮观察到的 mutation 会使最终激活检查失败并留待下一轮重建，最多产生可回源校验的假阳性，不能产生假阴性；禁止用 `HGETALL` 把无界 mutation 一次读入应用内存。

禁用、过期或软删除后的减计数失败只产生假阳性，认证仍会在数据库层失败；该异常必须记录指标并触发重建，不能直接恢复 `ACTIVE`。

## 后果与已接受风险

固定 v1 避免维护双版本元数据和版本切换，但每次 Leader 重建期间所有认证都会绕过 Bloom，PostgreSQL 负载高于双版本方案。Leader 丢失后，新 Leader 必须以更大的 fencing epoch 从清理步骤重新开始，不能假设前一次构建可以续传；旧 Leader 的迟到 Pipeline 命令由 Lua 拒绝。

Redis 故障不会产生认证假阴性，因为所有非 `ACTIVE` 情况都回源数据库。代价是 Redis 或构建状态异常期间无法利用 Bloom 拦截无效摘要，必须通过认证负缓存、数据库索引和外围并发门禁共同限制穿透压力。

每个 Receipt Set 初始化时保留一个固定哨兵，Lua 在增删前同时校验哨兵和全部 Bucket 的实际字节长度。这样可以区分“合法的空分片/零计数器”和 Redis Key 被驱逐、截断；后一种情况必须先切换为 `DEGRADED`，不能返回“肯定不存在”。

此 ADR 只豁免 API Key Bloom 的“双版本切换”要求；Bucket 分片、Receipt 元素级幂等、Lua 原子防溢出/下溢、分页构建、Fail Open 和可观测性要求仍然全部适用。

## 回滚

关闭 `app.api-key.enabled` 或关闭 API Key Bloom 拦截，使认证全部回源 PostgreSQL；撤销 Worker 的 `/v1/*` 路由可同时停止公开流量。固定 v1 Redis 数据可以等待 TTL/运维清理，不执行数据库物理删除，也不回滚已经产生的 API Key、映射或 Usage 记录。
