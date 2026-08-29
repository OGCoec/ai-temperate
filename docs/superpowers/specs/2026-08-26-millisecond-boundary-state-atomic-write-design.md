# 毫秒边界状态文件原子写入设计

## 目标

避免 Suite 和最外层 Master 在 Windows 上更新状态文件时，因为用户或并行证据采样器短暂读取
同一文件而发生共享冲突并终止整轮测试。

## 已确认根因

Suite 当前使用 `Set-Content` 直接覆盖 `soak-state.json`。运行时证据采样器会周期性读取该文件，
Windows 可能在读写重叠时拒绝写入。本轮 E-P1 正式功能已经通过，但 Suite 在进入最终验证时因该共享冲突退出。

## 设计

- Suite、Scheduler 和 Master 统一使用同一种有界原子发布模式。
- 先在目标文件同一目录写入临时 JSON，保证读取者不会看到半写入内容。
- 使用 `[IO.File]::Move($temporaryPath, $Path, $true)` 原子发布完整文件。
- 仅对 `[IO.IOException]` 执行最多 20 次、每次 50ms 的有界重试。
- 无论成功或失败都清理当前进程拥有的临时文件。
- `Save-State` 不再直接调用 `Set-Content`，统一调用原子写入函数。
- Master 的 `run-state.json`、`heartbeat.json`、`run-ledger.json` 和子进程配置也使用同一重试机制。
- Master 进入 PASS、PERFORMANCE_FAIL、FAIL 或 TEST_INVALID 终止态时，不再发布子心跳中已过期的 Application、Sampler 和 Suite PID。
- 文件共享冲突在有界重试耗尽后归类为“测试无效/证据发布环境失败”，不得误报为 Java 功能失败。
- 重试耗尽后仍抛出原始异常，不能掩盖持续性文件系统故障。

## 测试合同

扩展 Suite、Scheduler 和 Master 的三个编排合同测试，要求临时文件、原子覆盖、
20 次有界重试、正确的终止态 PID 发布和测试无效分类。

## 非目标

- 不修改 Java、JAR、数据库、Redis、RabbitMQ或压测阈值。
- 不修改日志归档行为。应用停止后，固定 `logs` 文件仍在哈希校验通过后归档到 Run 证据目录并删除源文件。
- 不启动服务或执行真实负载。
